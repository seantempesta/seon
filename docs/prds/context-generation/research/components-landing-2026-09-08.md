---
type: research
status: active
date: 2026-09-08
tags: [research, agent, render]
---

# Agent components landing

The retired-record cut is `67fe1675d`; settings is `e96001a7c`; this commit
contains the useful render pairs and data-returning documentation. Identity
aggregation in the protected debug-page loop remains an integration hunk,
linked below. The earlier reset-needed flag is **superseded** by the final
live verification: default has the new record shape and no checked retired
datoms. No default lifecycle operation was performed.

Read AGENTS.md and the turn PRD §0, §1a, §4/§4a, §13, and §17 end to end,
plus the plan README and working edge. The AGENTS.md lane-rules preamble
is its copy of PRD §10; AGENTS.md has no separate §10 heading.

## Baseline observation

Playwright with installed Chrome inspected default at
`http://127.0.0.1:7994/ns/my.agents.juniper/debug` on 2026-09-08.
The page renders id, cluster, instructions, namespace, and run separately.
Plan AI executes current/ready/blocked; plan HTML says `map 3 items, depth 0`.
Settings has no stored value. Stored `dir` evaluations return symbol vectors
and also print function names. The context algorithm reports
`Not yet available: seon.eval/of-agent`.

Default PID 91455: runtime_status reports health and Flow unknown (read
 timeout); a subsequent JVM `(+ 1 1)` returns 2 in 3 ms. Existing issue:
[component probe timeout](../../../seon/issues/default-component-probe-times-out-after-adoption.md).
Default was not stopped, reforked, or restarted.

## Dependency ledger

- Datahike: owned refs and pull; `reference-code/datahike/src/datahike/db/transaction.cljc`
  owns `:db.fn/call`, deciding against the writer's database.
- Schema bridge: `src/seon/schema/datahike.clj` derives component facets;
  `resources/seon/schemas/seon.agent.edn` already declares plan/settings refs.
- Overlay: `src/seon/ai.clj` agent-overlay derives keys from
  `:seon.config/agent-overlay` and follows the settings component.
- Plan: `src/my/plan.clj` already follows the plan component, introduced
  by `74b5b4b05`; inspect and improve that owner in place.
- Render integration: page-feed owns web.clj and render internals. Any needed
  change there is recorded here rather than applied to that lane's files.

## Required changes in protected render owners

The following readers still refer to the deleted agent cluster ref. In
`src/seon/render.clj`, `custody-cluster-name`, and
`src/seon/render/transcript.clj`, `agent-config`, remove the agent-id input
and the agent/id + agent/cluster joins from the cluster-name query; the
branch's `[_ :seon.cluster/name ?cluster-name]` fact supplies its cluster.
Update each local caller to pass the database only.

`src/seon/render/agent.clj`, `agent-ai`, must not infer idle from missing
`:seon.cluster.agent/run`. Query `seon.cluster.run/open-for-agent` with the
unit's database and agent lookup, or render identity only. Missing a retired
pointer does not prove idle.

The page must call the agent entity's declared pair once for its scalars,
then each component's pair. Its current per-attribute loop bypasses the
agent's useful identity renderer. This is in the protected page-feed owner.

Protected test `test/seon/cluster/agent_test.clj` still writes the retired
pointer at its fixture around line 608 and queries it around lines 1057 and
1778. Remove the fixture pointer; derive the open turn through its agent ref
and absence of closed-at. These edits were not applied to another lane's file.

## First cut evidence

The isolated subject gate passed 38 tests / 273 assertions, zero failures
and errors (snapshot `run.Qu9gPF`, coordinator/test phase 87 seconds).
The first run exposed an incorrect pulled-ref output contract in the new
query; returning the turn id corrected it. The next run exposed two stale
identity expectations; the branch supplies the cluster for every agent.

The platform attempt `run.fv5GcQ` failed before assertions because pool-4
had no prepared classpath. Its three-worker retry uses the documented
processor-count workaround; see
[worker-count issue](../../../seon/issues/platform-worker-count-exceeds-prepared-checkouts.md).

Scratch root `tmp/components-root`, cluster `components`, HTTP 7809, was
reforked from publication `6aa09695-1061-5ba5-ac8d-65775ce553d7` and seeded
with the updated Juniper fixture. MCP JVM verification returned in 4 ms:

```clojure
{:agent-keys [:db/id :seon.agent/plan :seon.cluster.agent/id
              :seon.cluster.agent/namespace]
 :open-turn "bootstrap:juniper"
 :retired-schemas [nil nil nil]}
```

The schema entries checked were run, cluster, and instructions. There was
no agent toolkit declaration to remove. The scratch armer was paused using
core.async.flow's own pause-proc and its ping confirmed `:paused` before
seeding; Juniper exists and its attempt query returns `[]` (8 ms). No model
turn was used for this proof. The generated CSS artifact was copied into
the worktree's resources after the first screenshot exposed its absence.

[Scratch screenshot](components-step1-2026-09-08.png) and
[exact captured AI/HTML column text](components-step1-2026-09-08.json) show
that cluster, instructions, and run blocks are gone. Identity remains split
by the protected page loop, and the existing plan HTML remains generic at
this first cut. These are not final-render acceptance screenshots.

Platform retry passed 82 tests / 486 assertions, zero failures and errors,
with three workers (snapshot `run.Dz4uN9`, coordinator/test phase 90 seconds).
The subject and platform gate used HEAD plus only the owned paths. The
subsequent source edits only corrected nearby documentation/teaching text.


## First commit adoption

Commit `67fe1675d` adopted onto default successfully; publication
`6aa09972-aadb-56cc-a9dd-ee5ec378034c`. I inspected the default page with
Playwright and viewed its screenshot. Instructions and run blocks disappeared;
a legacy cluster datom still appears. **RESET NEEDED: `67fe1675d`** for the
orchestrator's single refork. Default was not stopped or restarted.


## Settings component

`my.agent/settings!` decides the component identity inside Datahike's writer
and updates it in place. The schema's `:seon.schema/references` supplies the
key set; there is no second dial roster. The turn bound now declares its
per-agent overlay flag. Model settings already followed the component;
evaluation/turn passes merge it into their supplied handle, and the agent's
completion wait reads its override. Missing overlay schema references return
a typed unknown-shape value.

The subject gate passed **17 tests / 130 assertions**, zero failures/errors
(`run.QjAvuD`, coordinator/test phase 87 s). The canonical property took
24,649 ms after the query change. Its earlier fast run was interrupted after
a thread dump proved it rebuilding the entire schema projection per overlay
read; the dump is summarized in the existing
[projection issue](../../../seon/issues/seon-db-reads-rebuild-the-projection-per-call-when-none-is-handed.md).
The regression verifies repeated writes preserve one component, agent isolation,
a changed override changes turn admission, and seven real SCI evaluations
receive the explicit agent deadline.

Scratch adoption `6aa09c1e-d8fb-56db-b1a3-b59707277291` completed. A live update
and read returned in 48 ms:

```clojure
{:settings {:seon.config.eval/time-limit-ms 2500
            :seon.config.run/max-episode-runs 4}
 :same-component true
 :dial-count 29}
```

The dial count is a dated observation of the schema query. The fixture now
seeds these two overrides on its single settings component. The
[scratch screenshot](components-step2-2026-09-08.png) and
[AI/HTML text](components-step2-2026-09-08.json) were captured from the live
page and inspected; useful presentation is the next cut.

Settings platform gate: **82 tests / 486 assertions**, zero failures/errors
(`run.oLleW9`, coordinator/test phase 112 s), with at most three workers.


## Render evidence and remaining integration

Scratch in-place adoption `6aa09f5e-142a-5348-9da2-55f436167566` loaded the
final render functions. The final subject gate at HEAD `985a830b5` plus only
owned paths passed **25 tests / 143 assertions**, zero failures/errors
(`run.wdrYrQ`, coordinator/test phase 56 s). The first pass exposed old
identity-string expectations; a later fixture incorrectly submitted two
reader events to the one-event evaluator. The corrected test runs the forms
in one `do`, and the actual browser independently exercises separate forms.
The real SCI checks cover identity data, implicit settings arguments, and
`doc`/`dir` data with no duplicate stdout. No mocked SCI context was used.

The plan shows its own objective, current step, progress, and every derived
step state. Settings shows all schema-declared dials, values, and inheritance;
the table wraps within the debug column. Identity links its name, namespace,
and steward. No whole-component generic-printer fallback remains in these
functions. The old `whoami` text API remains callable; the identity renderer
now generates the data query directly.

Screenshots inspected by the components lane:

- [Actual scratch debug page](components-final-debug-2026-09-08.png), with
  [exact captured AI/HTML column bytes](components-final-debug-2026-09-08.json).
- [Plan in the debug column](components-final-debug-plan-2026-09-08.png) and
  [settings in the debug column](components-final-debug-settings-2026-09-08.png).
- [Identity](components-final-identity-2026-09-08.png),
  [plan](components-final-plan-2026-09-08.png), and
  [settings](components-final-settings-2026-09-08.png) are the actual live
  component-function results displayed in a standalone evidence document.
  The [probe](components_probe_2026_09_08.clj) calls those functions against
  the scratch database; it does not implement another renderer. The
  [HTML](components-final-2026-09-08.html) uses a copied generated stylesheet
  so it remains inspectable after scratch cleanup.

The live debug page still displays id and namespace separately because its
protected attribute loop never invokes the aggregate agent pair. The agent
schema now points at the existing `seon.cluster.agent` identity owner. The
[proposed web.clj hunk](components-web-proposed-2026-09-08.patch) groups this
identity and derives component membership from installed `:db/isComponent`
facts, retaining reverse concerns. **The hunk is not applied or claimed as
verified.** It names a narrow agent integration; the renderer owner may
apply the same rule generically. Delete the now-unreferenced identity pair
and selector in protected `src/seon/render/ns.clj:17–41` when integrating,
rather than retaining two owners.

Protected `test/seon/sci/eval_test.clj` still asserts that `dir` prints names
and `doc` prints formatted contract prose around line 1085. Replace that
retired expectation with returned public rows and zero stdout; the owned
`test/seon/sci/documentation_test.clj` verifies this behavior on the canonical
armed SCI harness. That protected file was not edited.

Default adoption after `e96001a7c` reached JVM instrumentation and failed
with `:seon.instrument/registration-failed` / `:malli.core/invalid-schema`
for `:seon.render/cache`. A following Playwright navigation timed out at
30 seconds. The contemporaneous publication read from the hosting root,
not the invoking worktree; `--changed` is not an isolated publication
snapshot. Page-feed subsequently landed `985a830b5`, whose schema declares
that key. No default lifecycle operation was performed. This is a measured
integration boundary, not a failed component gate; final adoption will be
attempted after this commit.

## Exact generated AI sources

These are the bytes returned by the live component functions. The debug
JSON above additionally records evaluated plan/settings results, including
their measured evaluation times. Identity's generated query is executed by
the real SCI identity regression; its aggregate debug block awaits the
protected web hunk. The [source artifact](components-final-2026-09-08-sources.edn)
retains the strings losslessly.

`:seon.cluster.agent/agent`

```clojure
; This is my identity and namespace; its steward is responsible for it.
(seon.db/pull (quote [:seon.cluster.agent/id #:seon.cluster.agent{:namespace [:seon.ns/name #:seon.ns{:steward [:seon.cluster.agent/id]}]}]) [:seon.cluster.agent/id "juniper"])
```

`:seon.agent/plan`

```clojure
; Your plan. (dir my.plan) is its API; (doc my.plan/complete!) explains one form.
(my.plan/current)
(my.plan/ready)
(my.plan/blocked)
```

`:seon.agent/settings`

```clojure
; Your overrides inherit omitted defaults; change one with (my.agent/settings! {:seon.config.eval/time-limit-ms 5000}).
(my.agent/settings)
(seon.ai/agent-setting-attributes)
```


Final platform gate: **82 tests / 486 assertions**, zero failures/errors
(`run.91KD6h`, coordinator/test phase 113 s), at HEAD `985a830b5` plus owned
paths. `SEON_TEST_WORKERS=3` and the previously documented processor-count
workaround were used throughout. No full suite was run. Scratch source was
HEAD `2531b2e70` plus the components files; the final isolated gates additionally
include landed page-feed `985a830b5`. The proposed protected web hunk is not
part of either proof.


## Final default verification and cleanup

Implementation commits, in requested order:

1. `67fe1675d` — retired agent record fields and open-turn derivation.
2. `e96001a7c` — one settings component and consuming overrides.
3. `8b48a7c47` — useful render pairs and data-returning `doc`/`dir`.

Final default adoption succeeded at source commit
`6aa09fd1-1bbd-52ab-a9ae-a4a290e2cad6`, digest
`bbf73f197859463cd797fd6e103a4654aa6e85f696f8d1ce15bb1c249c0194ae`.
Reseeding the updated Juniper fixture returned its objective and current
step in 96 ms. The [final read-only probe](components_default_verify_2026_09_08.clj)
verified the complete agent key set and absence of the checked retired
attributes in 268 ms (normalized below):

```clojure
{:agent-keys [:db/id :seon.agent/plan :seon.agent/settings
              :seon.cluster.agent/id :seon.cluster.agent/namespace]
 :retired-datoms #{}}
```

This supersedes the first-checkpoint **RESET NEEDED** flag; no additional
reset is requested on this evidence. The default cluster was never stopped,
reforked, or restarted by this lane.

I inspected the [final default debug page](components-default-final-2026-09-08.png),
[plan](components-default-final-plan-2026-09-08.png), and
[settings](components-default-final-settings-2026-09-08.png). The
[captured AI/HTML bytes](components-default-final-2026-09-08.json) show useful
component content and no retired cluster/instructions/run block. Identity
is still split by the protected per-attribute page loop. The proposed hunk
passes `git apply --check` at later page-feed commit `baa1dde54`; it remains
unapplied and unverified as running code. The earlier `:seon.render/cache`
adoption failure is no longer the final default state.

Cleanup used the creating checkout's operator to stop scratch PID 8533,
start instant `2026-09-08T23:16:10.344Z`, generation
`cb80f90a-b2a7-4a79-ad7c-126fe821e6d2`. Its process-table absence was checked
before deleting `tmp/components-root` and removing `tmp/components-wt`.
The main checkout's operator had incorrectly returned an empty process
census for that same explicit root; the
[operator issue](../../../seon/issues/operator-down-misses-a-live-scratch-jvm-from-another-checkout.md)
records the exact discrepancy without guessing its cause.

Holderless owned failures `run.fv5GcQ`, `run.EOVWot`, and `run.m5UkjS` were
removed after checking lane status and the process table. Their subjects
had been re-observed by the passing final gates. Successful isolated roots
were removed by the runner itself. Source symlinks were not followed during
cleanup. Unrelated working edits and other lanes' roots were preserved.

## Follow-up: message entities and declared reverse concerns

The 18:10 assignment was probed on the live default page before editing.
The registry probe returned the existing `:seon.render/units` schema (78 ms)
and no declarations using it. This change reuses that property on the agent
entity schema: message recipients, turns, and faults are its reverse concerns.
No new property or production key roster was added. The PRD §13–§17 was
read again; the previously requested authorities were read end to end during
this lane's initial cuts.

Dependency ledger: Datahike pull's reverse-ref semantics remain owned by
`reference-code/datahike/src/datahike/pull_api.cljc`; the first-party consumer
is `src/seon/render/web.clj:1617`. Authored Malli metadata is read through
`src/seon/schema/form.cljc:17` (`attr-form-properties`), using the projection
already handed to the caller. Message reads remain `my.message/read`, backed
by `seon.db/pull`; `seon.cluster.message/render-inbox-ai` calls the message
entity pair once per acquired message, ordered by its timestamp and id.

The message teaching form now returns the durable message map, including
sender, recipient, timestamp, content, and message identity. HTML keeps
attribution and timestamp separate from authored content, preserves newlines,
and shows the sender-addressed `my.message/send` expression with this message
as `about`. An absent sender is stated explicitly; no recipient is invented.

Protected integration: the updated
[web hunk](components-web-proposed-2026-09-08.patch) is **not applied**. It
includes the earlier scalar grouping and replaces discovery of every installed
reverse ref with the matching entity schema's `:seon.render/units` declarations.
Until page-feed integrates it, the live page can still show undeclared reverse
refs and duplicate messages beneath the inbound request shape. The pair and
schema declaration alone do not prove that page filtering has shipped.

The stored `:seon.def/agent` and `:seon.def/ns` rows must be deleted by turn-cut
under PRD §14/§15. They receive no render pair in this change. Admission source
and route data refs likewise receive no concern declaration. The raw reference
graph may still inspect connections; they are not agent concern blocks.

Two old assertions in the protected history integration test still expect
`seon.cluster.message/format-ai` (`test/seon/render/transcript_test.clj:430`
and `:744`); when that integration is updated, they must expect
`my.message/read` and a printed data result. No protected render source or test
was edited here.

The armed subject probe also exposed a stale generator producing nil for the
required `:seon.config.message/max-chain` input. Its valid-history generator now
uses positive bounds; this does not weaken the production contract. Explicit
`(seon.db/db)` in the generated message read avoids relying on positional
argument supply in the fixture and works through the ordinary SCI database API.

Scratch verification used `tmp/components-root`, cluster `components`, HTTP
7809, PREPL 57462, published source `6aa0cece-8ed0-5050-be6f-89eadade587c`.
Juniper was reseeded (222 ms). The armer was paused before seeding; only root
was armed. Root's already-started bootstrap added a task and a fault message;
the final capture therefore contains four messages, including the two authored
fixture messages. Root's scratch graph was subsequently paused as well. These
are observed message contents, not a claim of healthy bootstrap execution.

The final scratch message column contains four `my.message/read` evaluations,
2,343 UTF-8 bytes including its `AI` label, and no generic printer fallback in
the message HTML. Exact source is
[recorded here](components-messages-scratch-2026-09-08.ai.clj); exact evaluated
AI and HTML column text is in
[the browser capture](components-messages-scratch-2026-09-08.json).
The [message screenshot](components-messages-scratch-2026-09-08-messages.png)
was opened and inspected; the [whole page](components-messages-scratch-2026-09-08.png)
also records the remaining protected integration gaps.

Exact first evaluated message bytes from that capture:

```clojure
; Read this message; reply with (my.message/send sender-id text message-id).
my.agents.juniper=> (my.message/read "design-lab/root-to-juniper/1" (seon.db/db))
#:seon.repl{:value #:seon.cluster.message{:at #inst "2026-09-06T19:35:00.000-00:00", :content
  "Please make your current plan and the messages you receive easy to understand together. Start by inspecting the data connected to your agent entity.",
  :from [:seon.cluster.agent/id "root"], :id "design-lab/root-to-juniper/1",
  :to [:seon.cluster.agent/id "juniper"]}, :ms 99}
```

The isolated subject gate on HEAD `f01a9a824` plus only the three owned source,
schema, and test paths passed **20 tests, 59 assertions**, zero failures/errors.
The new test verifies that reverse concerns are actual schema declarations.

The platform gate passed **82 tests, 486 assertions**, zero failures/errors,
with `SEON_TEST_WORKERS=3` and `-XX:ActiveProcessorCount=6`. The browser's fault
message was subsequently traced to the protected feed writer, **not** bootstrap
execution: `web.clj:2865` casts an absent package basis number. The durable
fault evidence and exact ownership boundary are filed in
[the feed issue](../../../seon/issues/feed-writer-casts-an-absent-package-number.md).
This corrects the provisional bootstrap attribution above; the message itself
names the interrupted bootstrap turn, which is not the throwing function.

### Follow-up default adoption and final observation

Commit **`03d3bfb1b`** was adopted with `bin/seon init --dev default --changed`
using the three owned source/schema/test paths. It converged at source
`6aa0cf6a-730f-5ba3-960e-f00e731bc9f6`, digest
`7769bc5c1a72d7ce3bca8b85a91ffbe65db82cd47c89cdc111066a30979754b3`.
No default stop, refork, or restart occurred.

The first browser observation falsified adoption convergence: the JVM renderer
returned new source, but the page retained the old inbox source and HTML.
SCI-mode MCP and the instrumented `seon.render/shared-cache` accessor timed out
at 10 seconds. Simple JVM probes remained 2–3 ms. Reading the cache as existing
data and clearing its disposable contents returned in 2 ms:

```clojure
(let [c (:seon.cluster.loop/cluster
         (get @seon.operator.runtime/running-instances "default"))
      state (:seon.sci.eval/projection-state c)
      cache (:seon.render/cache @state)
      before (vec (keys @cache))]
  (reset! cache {})
  {:cleared-render-cache-keys before})
```

The next default browser capture showed **eight message read forms**, **4,900
UTF-8 AI-column bytes** including its label, and **two reply expressions**.
I opened and inspected the
[default message screenshot](components-messages-default-2026-09-08-messages.png).
[Full-page screenshot](components-messages-default-2026-09-08.png),
[exact evaluated AI/HTML bytes](components-messages-default-2026-09-08.json), and
[generated source bytes](components-messages-default-2026-09-08.ai.clj)
are retained. The two additional messages are feed faults observed during the
probes, not fabricated renderer fixtures. The first message's evaluation time
in the final default capture is `:ms 71`.

The confirmed stale-render observation and MCP timeouts are recorded in
[the adoption probe issue](../../../seon/issues/default-component-probe-times-out-after-adoption.md).
The missing invalidation edge remains page-feed's protected integration work.
Default still shows undeclared reverse blocks, including stored defs, because
the proposed web hunk is deliberately unapplied. Their deletion belongs to
turn-cut, as requested. This landing does not claim that filtering has shipped.

Cleanup: the creating checkout's operator stopped scratch PID 32693, generation
`ab935d18-9b3b-4043-af90-eb97959ca4c2`, and reported its store lock free. The
process was absent before removing the owned worktree and scratch root.
