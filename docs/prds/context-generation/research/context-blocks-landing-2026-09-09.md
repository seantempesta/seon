---
type: research
status: active
tags: [agent, context, render]
---

# Context blocks — 2026-09-09

Read AGENTS.md's verbatim lane rules and turn PRD §10, §13–§16, and §18 end to end before implementation. The initial default page was read before source inspection. Default was alive (PID 40078, port 7994); MCP runtime status returned health/Flow unknown with `Read timed out`. JVM evaluation subsequently returned 2 from `(+ 1 1)`. This is a failed health observation, not proof of a dead cluster.

## Initial observation

The would-be system turn has no help/instructions, exposes a raw identity pull, emits three plan reads, shows two ExceptionInfo objects for blocked steps, dumps the complete settings keyword set after overrides, and emits `(my.message/inbox {})`. Four inbox messages include two fixture messages and two unrelated probe messages. The saved prefix contains only `(+ 1 1)`; historical probe turns also remain in the debug view. No provider was invoked by this assignment.

## Dependency ledger and verified boundary

- SCI's call-preparation hook (`reference-code/sci/src/sci/core.cljc:310`) receives evaluated arguments and returns prepared arguments or a reduced refusal. `src/seon/call_preparation.clj:657` derives shorter calls from declared contracts; request-map defaults belong there.
- Datahike's transaction function (`reference-code/datahike/src/datahike/db/transaction.cljc:1152`) receives the transaction database. Existing plan mutations make ownership and position decisions there.
- `src/seon/db.clj` owns pulls and read evidence; `src/seon/plan.clj` owns the moved plan queries, transactions, and render functions. No alternate storage family was introduced.
- With the live database's projection explicitly supplied, JVM `my.plan/blocked` returned ordinary maps containing dependency strings under `:my.plan.item/needs`. That attribute declares stored refs. The AI projection treated these strings as entity refs and failed. Derived summaries now carry the existing `:my.plan/needs` stable-reference maps.

## Slice 1 — `105acca21`

Positional operations move to system namespaces; `my.*` exposes request-map calls. Fully namespaced keys remain mandatory; §18's abbreviated key examples do not introduce unqualified attributes. The section's blanket request-map rule governs its two positional plan examples.

Verification and live adoption results will be recorded with the slice commit. Scratch root: `tmp/context-blocks-root`, no-provider configuration. Default has not been stopped, restarted, or reforked.

### Slice 1 verification

- Fast: 43 tests / 430 assertions / zero failures or errors.
- Path-limited gate: 47 tests / 456 assertions / zero failures or errors across
  the my API namespaces, filesystem/edit predicates, and real SCI shown-text regression.
- Expanded gate: 71 tests / 574 assertions / 6 failures / 3 errors. The
  detached HEAD-only baseline at `0b3d31b26` reproduces those same failures:
  24 tests / 123 assertions / 6 failures / 3 errors in call-preparation and
  bootstrap. This is a pre-existing consumer-fixture boundary, not an
  attribution to another session. Details are recorded in
  `docs/seon/issues/turn-consumer-fixtures-read-retired-result-storage.md`.
- Fresh scratch fork, no-provider: Juniper seeded through the maintained
  fixture installer and read by HTTP at port 7833. The HTML has 65,811 bytes;
  rendered page text has zero `ExceptionInfo` strings, four bare inbox calls,
  and zero inbox calls carrying `{}`. Blocked dependencies are stable maps.
- Computer Use reports no browser available. Page-text verification is
  complete; visual layout and browser repaint are not claimed.

While slice 1 ran, owner commits `02c77c542` and `0b3d31b26` amended §18/18a.
The owner explicitly selected the amended requirements at 11:22; §18 and
§18a were reread end to end before continuing.
- Path-limited platform gate passed: 83 tests / 490 assertions / zero failures or errors. Slice commit: `105acca21`.

## Slice 2 — agent attribute family

All agent attribute and contract keys move to `:seon.agent/*`, including
call-preparation inputs, lifecycle graph arguments, stored refs, fixture
writers, query readers, and tests. The existing `seon.cluster.agent`
namespace remains the lifecycle owner. Its schema resource merges into
`resources/seon/schemas/seon.agent.edn`; there is one declaration population.

**RESET NEEDED**: the rename changes stored identity and ref attributes.
Default must be reforked once by the orchestrator, batching pending schema
changes. This lane does not stop, restart, or refork default.

The default adoption attempt after slice 1 refused at schema population:
`Predicate seon.edit/valid-form-operation? has no admitted callable in the corpus projection.`
The supported JVM REPL loaded the moved predicate owners and registration
call sites successfully. This is the previously recorded boundary in
`docs/seon/issues/a-new-core-predicate-and-its-schema-cannot-be-adopted-in-place.md`.
The default HTTP page still showed old blocked ExceptionInfo objects and
`(my.message/inbox {})` at that observation; no successful adoption is claimed.

Fresh scratch publication `6aa1977d-e2e7-55a5-b435-bb333566f307` booted
with the renamed identity installed. The maintained Juniper installer
returned objective `Improve Juniper context inspection`. HTTP debug output
was 65,159 bytes and showed `[:seon.agent/id "juniper"]` plus bare inbox
reads. An explicit JVM read returned:

```clojure
{:identity {:seon.agent/id "juniper"
            :seon.agent/namespace {:seon.ns/name my.agents.juniper}}
 :blocked [{:my.plan.item/id "juniper/compare-changed-results"
            :my.plan.item/title "Compare refreshed results"
            :my.plan.item/expected-result "The comparison shows the previous and refreshed results together, with the relevant changed input."
            :my.plan/needs [{:my.plan.item/id "juniper/render-plan"}]}
           {:my.plan.item/id "juniper/try-live-turn"
            :my.plan.item/title "Try the assembled context in a live agent turn"
            :my.plan.item/expected-result "Juniper identifies the current step and records a truthful plan update from the assembled context."
            :my.plan/needs [{:my.plan.item/id "juniper/compare-changed-results"}]}]
 :renamed-id-installed true}
```

The later default adoption attempt encountered the renamed population while
its loaded contracts still referenced `:seon.cluster.agent/routing`; it
refused with `:malli.core/invalid-schema`. No successful default adoption
is claimed. This reinforces the RESET NEEDED boundary.

The expanded rename gate also exposed stale message-surface expectations
from slice 1. Those tests now expect one request-map arity, generated map
reply examples, and a successful bare inbox call. Their real SCI fixture
now initializes the cluster environment through `config/apply!`. The
overlay test now checks its stated guarantee (every AI dial is overridable)
without incorrectly forbidding the declared evaluation and turn overrides.

### Slice 2 verification

- Corrected message fast loop: 20 tests / 59 assertions / zero failures or errors.
- Final path-limited gate: 45 tests / 167 assertions / zero failures or errors.
- Path-limited platform gate: 83 tests / 490 assertions / zero failures or errors.
- Default remained live after the refused adoption: HTTP debug response
  71,308 bytes, old identity keys still present, four `ExceptionInfo`
  occurrences across the displayed projections.
- Test workers were capped at 3. No default lifecycle operation occurred.

Slice 2 commit: `3f07beb88` (**RESET NEEDED**). Its post-commit default
adoption again refused; default's REPL returned `2` in 1 ms and its debug
page remained available. No lifecycle operation was taken on default.

## Slice 3 — one help value

`seon.bootstrap/help` expands to the existing bootstrap owner's new
`help-value` read. The value is the exact §18a vector, with the namespace
substituted and Tools derived through the existing program-graph namespace
query plus public function/doc facts. It prints no separate instruction
text and has no topic dispatch. The identity renderer emits `(help)` first.

A real SCI/system-turn regression verifies the first stored evaluation,
13 one-line strings, all three ▲ warnings, derived Tools, no printed
output, source-version read evidence, and unchanged saved timing/handle
bytes. The test changes unrelated agent data (evidence stays current),
then changes the help definition's source (evidence becomes stale).
The fixed prose is read from its owning function's indexed source solely
to record that dependency; it is not stored as another instruction row.

Fast loop: 3 tests / 30 assertions / zero failures or errors. The first
scratch adoption attempt encountered `Clj-kondo cache is locked by other
thread or process`; the operation was retried without touching another
session or its files.

Live adoption falsified a second assumption: the qualified help macro was
new, but the bare `clojure.core/help` still expanded to the boot-time
`situation` call. Acquisition now binds the bare name to the acquired
macro Var itself. The regression verifies that identity. The live probe
after adoption returned exactly:

```clojure
[(seon.bootstrap/help-value) (seon.bootstrap/help-value)]
```

Scratch source commit `6aa19efc-cc73-565c-bc5c-0e45b413755f` was adopted
in place. After compaction, system turn `fecf3fb45f4d` stored the new help
vector first. Its debug HTTP response was 74,539 bytes; the stored help
had all 13 lines, three ▲ warnings, a result handle and `:ms 94`. The
would-be system turn was then empty because every read was unchanged.
The old plan forms and settings keyword list remain for slice 4.

Final focused gate: 6 tests / 57 assertions / zero failures or errors.
Final platform gate: 83 tests / 490 assertions / zero failures or errors.
Both gates capped workers at 3 and isolated only this slice's paths.
The cache rebuild diagnosis and bounded retries are recorded in
`docs/seon/issues/source-publication-cache-contention-hides-dependency-analysis-failure.md`.
The normal publication classpath rebuild completed, followed by successful
scratch adoption. No other lane's process was operated.

Slice 3 commit: `e506861ac`. Its default adoption refused at
`:seon.config/agent-overlay`: the old running projection still named
`my.agent/render-settings-ai`, whose program row has moved to
`seon.agent/render-settings-ai`. HTTP remained available (71,301 bytes).
This is a loaded publication/projection boundary, not a failing slice test.
The pending **RESET NEEDED** from `3f07beb88` remains; default was not
stopped, reforked, or restarted.

## Slice 4 — forms that express my next action

The identity block now emits `(my.agent/identity)`; its derived scalar
values use `:my.agent/*`, preserving the stored namespace ref's meaning.
The plan emits `(my.plan/items)` and returns authored order, state,
`:my.plan/done-when`, and stable dependency refs. The completion criterion
projects the existing `:my.plan.item/expected-result` fact; there is no
second stored criterion. Changed-item returns now derive state from the
whole owned plan, so selecting a current item cannot return `:open`.
Completed items are no longer clipped by a read outside the AI renderer.

Settings emit only `(my.agent/settings)`, adding derived
`:my.agent/turns-left` from the turn owner's existing session bound.
`(my.agent/done)` returns the existing terminal wait disposition without
sending a message. `(my.test/run)` resolves the database's declared test
symbols in the caller's actual SCI context and uses `seon.test/run`, the
same runner and result writer as the platform.

The walk reads concern order from the matching schema's authored
`:seon.render/units`. An explicitly declared reverse form supplies an
inbox block even when no message entity exists. This fixes the regression
where an empty inbox vanished from system turn 0. Comments state my intent
before each form. Schema declarations now retain their actual namespace
ref as `:seon.schema/ns` at the reader/declaration seam; the namespace
renderer derives one count query from those rows, with no keyword-name
inference and no form when there are no declared keys.

The live turn-count probe exposed a necessary correction: system turns
were consuming the session bound, so a settings read could invalidate
itself each time a system turn stored it. The turn owner now excludes
turns whose plan was frozen in their identity transaction—the existing
writer fact distinguishing system source from an ordinary reply. Ordinary
open turns still consume the bound. The regression requires a second
system-turn call to append nothing and retain unchanged read evidence.

The first final slice-4 gate passed 50 tests / 372 assertions; platform
passed 83 / 490. The turn-count correction is being gated again below.
Scratch source `6aa1b1c1-6514-570e-b457-d8e0b501a445` converged in place.
Its HTTP page was 69,513 bytes, with zero `ExceptionInfo` occurrences and
zero `(seon.ai/agent-setting-attributes)` forms. System turn `145e968a2d41`
stored exactly help, identity, items, inbox, settings, in that order.

Final focused gate after the turn-count correction: 62 tests / 447
assertions / zero failures or errors, one worker. Successful scratch
adoption: `6aa1b2be-5ba4-508e-8cb1-d9283c29dc18`. The live repeat probe
returned exactly:

```clojure
{:first {:seon.turn/id "7691ba19caa6"}
 :second {}
 :statuses [:unchanged :unchanged :unchanged :unchanged :unchanged]
 :settings {:seon.config.eval/time-limit-ms 2500
            :seon.config.ai/no-provider true
            :seon.config.run/max-episode-runs 4
            :my.agent/turns-left 2}}
```

This probe completed in 1,154 ms. The two already-consumed ordinary turns
remain fixture residue until slice 6; system refreshes do not spend more.
The final gate and platform run serially with one worker because of the
recorded parallel published-base acquisition failure.

Final platform after that correction: 83 tests / 490 assertions / zero
failures or errors, one worker. **RESET NEEDED remains `3f07beb88`**;
this slice adds compatible schema facts and does not authorize a default
lifecycle operation.

Slice 4 commit: `e4372b061`. The post-commit default adoption again refused
because its loaded overlay projection names the retired
`my.agent/render-settings-ai`. Default HTTP remained 71,301 bytes.

## Slice 5 — plan writes and their documentation

`add!` derives an omitted id with `seon.id/digest` over agent and title.
The writer appends after the maximum sibling position, rather than using
the sibling count (which reused positions in sparse plans). `update!`
changes the supplied title, description, or completion criterion.
`complete!` defaults its completion instant; `current!` selects an open
item. All four return the changed item with its actual derived state.
Every `my.plan` call remains one optional request map.

The generic program fact `:seon.fn/doc-order` is admitted at both static
indexing and runtime declaration. `dir` orders by that fact, then symbol;
the plan writes declare the first four positions. There is no function-name
roster inside documentation rendering.

The real SCI regression starts with a sibling at position 8, adds an item
at 9 without supplying an id, changes its criterion, selects it, completes
it, and verifies the returned state after each write. It also verifies
all four names first in `dir`. Focused gate: 46 tests / 356 assertions /
zero failures or errors. Platform: 83 / 490 / zero failures or errors.

Live scratch `add!` returned:

```clojure
{:my.plan.item/id "aa1cc4264367"
 :my.plan.item/title "Verify live plan defaults"
 :my.plan/done-when "The returned item has its derived identity and state."
 :my.plan/needs []
 :my.plan/state :ready}
```

The MCP cluster SCI context has no calling agent, so this operational probe
supplied `:seon.agent/id "juniper"`; the scoped SCI regression omits it.
The live call completed in 3,293 ms. This scratch-only item is removed when
the scenario is reseeded. The first adoption reloaded the new indexer after
publishing, leaving its earlier derived rows without `:seon.fn/doc-order`;
that distinction was observed directly, not inferred from a converged flag.

The follow-up publication converged at
`6aa1b51c-8d7e-51fa-b990-af7431def128`. Live program rows then contained
`add! 0`, `update! 1`, `complete! 2`, `current! 3`, and the SCI query
returned exactly `["my.plan/add!" "my.plan/update!" "my.plan/complete!"
"my.plan/current!"]` in 80 ms. Default still needs the orchestrator's
single refork for `3f07beb88`; no additional destructive operation was taken.

The final path gate after the documentation wording update passed the real
SCI plan API regression: 1 test / 20 assertions / zero failures or errors.
The broader 46-test gate and 83-test platform gate above cover the same
implementation; the subsequent changes clarified docstrings only.

## Slice 6 — the order scenario and its real loop

The live installer and `seon.loop-proof-test` now share
`test/seon/context_blocks_fixture.clj`. Schema is admitted by ordinary
SCI replies through the actual agent graph. The fixture installs four orders:
Ada 60 + 55, Bea 100, Cy 40. The largest individual order and largest
customer total differ. Root asks for the largest customer, a new order of
40, and the new total; six dependent plan items cover query, aggregate,
transact, re-query, reply, and done. The installer replaces only Juniper's
scenario facts, removes its old messages/faults/plan items and setup history,
and stores the new opening. Program identities are retained.

Three root causes were falsified by the end-to-end regression. The SCI
admission selector dropped `:seon.schema/ns` after the reader produced it;
it now preserves that fact. The namespace renderer's return contract said
string instead of source, so its generated query never entered the opening;
it now declares source. Installed attribute selection uses Datahike's schema
map, including dynamically admitted attributes, and emits one count query.
The batch evaluator also continued past a terminal disposition; it now stops
at the existing completed/wait value, making `(my.agent/done)` end execution.

The fast loop passed 1 test / 86 assertions, including the six opening forms,
stored prompt equality, genuine compaction, changed-read ordering, the
115→155 transaction, delivery to root, a declared SCI test run through
`(my.test/run)`, and absence of a transaction authored after `done`.
The subsequent fixture cleanup adjustment is covered by the commit gate.

The old scratch context reproduced the already recorded
[development adoption environment boundary](../../../seon/issues/development-adoption-retains-old-web-service-inputs.md):
`seon.env/advance-projection!` received no replacement environment.
A fresh scratch fork admitted the same declarations successfully. Default's
source remains `6aa14a3f-fc45-564d-94f1-0946b2309c90`; after slice 5 its debug
page was fetched again and its adoption refused the old overlay renderer.
**RESET NEEDED remains for `3f07beb88` and the source accumulated through
this fixture commit.** Only the lane's scratch cluster was reforked.

Commit gate: 2 tests / 110 assertions / zero failures or errors.
Platform: 83 tests / 490 assertions / zero failures or errors, one worker.
The live reseed caught a distinction the agent-only test harness cannot model:
the cluster armer can still have an earlier wake in flight after the fixture
agent was disarmed. The live wrapper now uses the existing armer acknowledgement
before the shared installer's final disarm and history cleanup. Clearing history
before that acknowledgement allowed a concurrently opened turn to survive with
a count-derived identity that collided with the next turn. This was fixture
cleanup racing normal operation, not evidence against ordinary compaction
(which retains turn identities). The bounded installer reports admission or
closure failure rather than silently accepting missing schema.

The final live fixture uses one writer transaction for the authored plan,
orders, settings, and root message. This also removes a needless basis race
between a plan API call and the rest of a seed. The separate plan API remains
verified in slice 5 and in real SCI. Fresh scratch publication:
`6aa1bae9-3672-53d3-b43b-bf7125d57e4f`. The final opening contains exactly six
evaluations. I read the whole provider prompt below top to bottom. It has no
ExceptionInfo objects, keyword settings inventory, empty inbox request map,
probe messages, or no-provider reply. The two fixed help examples still reflect
the literal §18a text; the argument-shape discrepancy is recorded above and is
left for the required trial, as ruled.

Exact scratch provider prompt: **5,921 UTF-8 bytes**, SHA-256
`c7892d25b24e07d2d5e9575ef7ab9e3b7ac0a4383dc0b6fae53c7e0befc30c8d`.
The payload is the bytes between the following fences, excluding the newline
before the closing fence. The default capture follows after the orchestrator's
single refork; these scratch bytes are explicitly not labelled default.

```clojure
;; I should understand how this REPL works before I act.
my.agents.juniper=> (help)
#:seon.repl{:value ["You are at a Clojure REPL in your namespace my.agents.juniper. Every function in the program is callable."
  "Reply with ;; thinking comments, each followed by the form it plans. ▲ Send only comments and forms; the prompt my.agents.juniper=> is drawn for you."
  "▲ Forms are evaluated in order, and their results arrive in your NEXT turn. Act on a result only after you have seen it; do not complete a step in the same reply as the form that does the work."
  "Each form returns one #:seon.repl map: :value (or :error) is data, :out is anything printed, :result names the live value."
  "result/e... is a real symbol bound to the live value: evaluate it, pass it as an argument, or dig in with get-in and keys."
  "▲ When unsure how to call something, ask first: (dir my.plan) lists a namespace's functions as data; (doc seon.db/q) returns a docstring and contract as data."
  "Your plan is your instructions: (my.plan/items). The current step's :done-when says what done means. (my.plan/complete! id) when it is."
  "(my.message/inbox) is what you were sent. (my.message/send {:to \"root\" :content \"...\"}) sends. Sending a message does not end your turn."
  "(seon.db/q '[:find ...]) queries, (seon.db/pull '[*] eid) reads one entity, (seon.db/transact! [{...}]) writes. The database is your cluster's and is supplied for you."
  "A defn with :malli/schema becomes a durable function. A deftest becomes a durable test. (my.test/run) runs yours."
  "A mistake returns :error data, never an exception. Read :seon.error/message and try again."
  "Each reply is one turn. :turns-left in your settings counts down. (my.agent/done) ends your session early."
  "Tools: my.agent — Read and update my record through request maps. (done, identity, settings, settings!); my.background — Start and inspect capability requests that may finish later. (await, background, poll); my.edit — Edit source files only when their expected digest still matches. (exact, form, lines); my.fs — Read, write, inspect, and find files with bounded results. (glob, read, stat, write); my.message — The inter-agent message protocol, with optional request-map calls; call preparation supplies my database and identity. (decline, inbox, read, send); my.note — My durable notes through one request map per call. (add!, forget!, notes); my.plan — The calling agent’s plan protocol. Each operation takes one request map. (add!, blocked, complete!, current, current!, item, items, plan, ready, ready-subjects, steps, update!); my.run — Every run ends by calling `complete` or `wait`, with one request map. (complete, render-namespace-ai, usage-form, wait); my.shell — Bounded foreground argv-vector process requests. (run); my.test — Run the tests declared in my namespace. (run); my.web — Fetch web resources and search the configured provider. (fetch, search)"], :result result/e3b1c11b89513, :ms 353}

;; I should know my identity, namespace, and its steward.
my.agents.juniper=> (my.agent/identity)
#:seon.repl{:value #:my.agent{:id "juniper", :namespace my.agents.juniper, :steward "juniper"}, :result result/ed25ec5adc95c, :ms 56}

;; I should follow my plan and verify the current step's completion criterion.
my.agents.juniper=> (my.plan/items)
#:seon.repl{:value [{:my.plan.item/id "juniper/query", :my.plan.item/title "Query the orders",
    :my.plan/done-when "I have read the order ids, customers, and amounts.",
    :my.plan/needs [], :my.plan/state :current} {:my.plan.item/id "juniper/aggregate",
    :my.plan.item/title "Find the customer with the largest total", :my.plan/done-when
    "A grouped sum query identifies the customer and their total.", :my.plan/needs
    [#:my.plan.item{:id "juniper/query"}], :my.plan/state :blocked} {:my.plan.item/id
    "juniper/transact", :my.plan.item/title "Add an order of 40 for that customer",
    :my.plan/done-when "The transaction result identifies the new order.",
    :my.plan/needs [#:my.plan.item{:id "juniper/aggregate"}], :my.plan/state
    :blocked} {:my.plan.item/id "juniper/requery", :my.plan.item/title "Read the customer's new total",
    :my.plan/done-when "A fresh grouped sum query includes the new order.",
    :my.plan/needs [#:my.plan.item{:id "juniper/transact"}], :my.plan/state
    :blocked} {:my.plan.item/id "juniper/reply", :my.plan.item/title "Tell root the customer and new total",
    :my.plan/done-when "The sent message contains the customer and verified new total.",
    :my.plan/needs [#:my.plan.item{:id "juniper/requery"}], :my.plan/state
    :blocked} {:my.plan.item/id "juniper/done", :my.plan.item/title "Finish the session",
    :my.plan/done-when "All preceding plan items are complete.", :my.plan/needs
    [#:my.plan.item{:id "juniper/reply"}], :my.plan/state :blocked}], :result result/e301a93f8da98, :ms 56}

;; I should check my inbox for anything I need to respond to.
my.agents.juniper=> (my.message/inbox)
#:seon.repl{:value [#:my.message{:at #inst "2026-09-09T12:00:00.000-00:00", :content "Which customer has the largest total? Add an order of 40 for them and tell me the new total.",
    :from "root", :id "juniper/largest-customer"}], :result result/e77f0a99b3d48, :ms 58}

;; I should check my overrides and how many turns I have left.
my.agents.juniper=> (my.agent/settings)
#:seon.repl{:value {:my.agent/turns-left 20, :seon.config.ai/no-provider true, :seon.config.eval/time-limit-ms
  10000, :seon.config.run/max-episode-runs 20}, :result result/e7530319b29d2, :ms 57}

;; What data is in my namespace?
my.agents.juniper=> (seon.db/q (quote [:find ?attribute (count ?entity) :in $ [?attribute ...] :where [?entity ?attribute _]]) [:example/amount :example/customer :example/order])
#:seon.repl{:value [[:example/order 4] [:example/customer 4] [:example/amount 4]], :result result/ed308a58c8319, :ms 51}
```

The final gate after the single-transaction fixture simplification passed
1 test / 86 assertions / zero failures or errors. A later live capture still
matched all 5,921 bytes exactly and retained exactly six evaluations.

## Slice 7 — default capture and one paid comprehension trial

I re-read AGENTS.md and turn PRD §10, §13–§16, §18 and §18a end to end;
the amended §18a owns the single help vector. Slices 3–6 landed as
`e506861ac`, `e4372b061`, `c89f2fb93`, and `ee9feedb4`. The rename's
**RESET NEEDED** boundary is recorded with `3f07beb88`. During this slice I
observed default return in JVM PID 37586 with the new agent schema. I did
not stop, refork, or restart default. The exact default capture below is
from that new JVM, after reseeding the assigned Juniper scenario.

The committed [trial harness](help_trial_2026_09_09.clj) calls the actual
`seon.render/acquire-context!` owner and `seon.ai/complete`. It ranks priced,
credentialed model rows from default by estimated request cost, records its
admission before HTTP, and refuses an existing evidence path. One HTTP call
was made; no model reply was evaluated. No help wording was changed after
this run. [Raw evidence](help_trial_2026_09_09.edn) contains the exact trial
prompt, fixed questions, model price rows, reply, normalized usage and score.

| Measurement | Observed |
|---|---:|
| Selected model | `deepseek-v4-flash` |
| Estimated request cost, flash | $0.00088732 |
| Estimated request cost, pro | $0.00275703 |
| Estimated request cost, muse-spark-1.1 | $0.0115065 |
| Input / output / cached tokens | 2196 / 415 / 0 |
| Estimated actual cost at configured prices | $0.00042364 |
| Provider-owner latency | 3737 ms |
| Numbered comprehension cues | 7 / 7 |
| Structural checks | 3 / 5 |
| Total | **10 / 12** |

The failed checks were `:no-prompt-marker` and `:syntax`: the reply printed
`my.agents.juniper=>` and invented a `#:seon.repl` result, including customers
and amounts absent from the fixture. Its query parsed, but queried every
datom rather than restricting to order attributes. The scorer's seven text
checks are lexical cue checks, not a semantic correctness proof. Its five
structural checks use the Clojure reader, the actual Datalog parser, SCI
resolution, the program graph and a query over verdict facts. In particular,
passing argument shapes does not prove that a query answers the right task.
The observed reply defect is tracked in
[the trial issue](../../../seon/issues/help-trial-copies-prompt-and-invents-results.md).

**Evidence limitation:** the actual paid prompt was 6,657 UTF-8 bytes,
SHA-256 `3336e4f1bda362f3fe23acfdb3ed69e14f95ad76e016847538890b4eea07d249`.
It contained the six opening evaluations but also two turn-backstop notices
inside the inbox value. This is not reported as a clean-fixture trial. The
preflight originally checked evaluation sources and order count; after this
observation it also refuses extra inbox message identities and captures from
the same immutable database value it checked. This admission improvement
made no second provider call and did not alter the saved reply or score.
The fixed help function's code SHA-256 was
`e60dbcccc6ed371fc6f68d68d90c8458c56824a83073b8a01f822ca5cd9d2e12`.

The live armer can recreate Juniper work during reseeding: one installer
attempt returned `agent-already-running`, and two changed-read evaluations
appeared before the opening. I then disarmed only the assigned fixture
agent, cleared its setup history and generated the opening once. The next
capture had exactly six evaluations, one root message, four orders and
`:my.agent/turns-left 20`. This is a live fixture/turn-loop boundary, not a
claim that passing the isolated loop regression proves the concurrently
operated default stays frozen. Related foreign work is already recorded in
[the seeded-opening issue](../../../seon/issues/seeded-opening-stores-no-read-evidence-so-the-first-wake-re-emits-everything.md).
That issue also owns the stale `my.run` description and internal helpers
exposed by the correctly derived Tools line. I did not edit that lane's file.

I read this final default prompt top to bottom. It contains no ExceptionInfo,
settings keyword inventory, empty inbox request map, probe messages or fault
notices. HTTP GET of default's debug page returned 200 after the capture;
this is an HTTP observation, not a claim of browser paint (CUA was unavailable).

Exact clean default prompt: **5,921 UTF-8 bytes**, SHA-256
`d696642f0d18305a6773c1a20e260d8821bb86a53921fd9a1434cb1925cc077e`. The payload is between these fences,
excluding the newline immediately before the closing fence.

```clojure
;; I should understand how this REPL works before I act.
my.agents.juniper=> (help)
#:seon.repl{:value ["You are at a Clojure REPL in your namespace my.agents.juniper. Every function in the program is callable."
  "Reply with ;; thinking comments, each followed by the form it plans. ▲ Send only comments and forms; the prompt my.agents.juniper=> is drawn for you."
  "▲ Forms are evaluated in order, and their results arrive in your NEXT turn. Act on a result only after you have seen it; do not complete a step in the same reply as the form that does the work."
  "Each form returns one #:seon.repl map: :value (or :error) is data, :out is anything printed, :result names the live value."
  "result/e... is a real symbol bound to the live value: evaluate it, pass it as an argument, or dig in with get-in and keys."
  "▲ When unsure how to call something, ask first: (dir my.plan) lists a namespace's functions as data; (doc seon.db/q) returns a docstring and contract as data."
  "Your plan is your instructions: (my.plan/items). The current step's :done-when says what done means. (my.plan/complete! id) when it is."
  "(my.message/inbox) is what you were sent. (my.message/send {:to \"root\" :content \"...\"}) sends. Sending a message does not end your turn."
  "(seon.db/q '[:find ...]) queries, (seon.db/pull '[*] eid) reads one entity, (seon.db/transact! [{...}]) writes. The database is your cluster's and is supplied for you."
  "A defn with :malli/schema becomes a durable function. A deftest becomes a durable test. (my.test/run) runs yours."
  "A mistake returns :error data, never an exception. Read :seon.error/message and try again."
  "Each reply is one turn. :turns-left in your settings counts down. (my.agent/done) ends your session early."
  "Tools: my.agent — Read and update my record through request maps. (done, identity, settings, settings!); my.background — Start and inspect capability requests that may finish later. (await, background, poll); my.edit — Edit source files only when their expected digest still matches. (exact, form, lines); my.fs — Read, write, inspect, and find files with bounded results. (glob, read, stat, write); my.message — The inter-agent message protocol, with optional request-map calls; call preparation supplies my database and identity. (decline, inbox, read, send); my.note — My durable notes through one request map per call. (add!, forget!, notes); my.plan — The calling agent’s plan protocol. Each operation takes one request map. (add!, blocked, complete!, current, current!, item, items, plan, ready, ready-subjects, steps, update!); my.run — Every run ends by calling `complete` or `wait`, with one request map. (complete, render-namespace-ai, usage-form, wait); my.shell — Bounded foreground argv-vector process requests. (run); my.test — Run the tests declared in my namespace. (run); my.web — Fetch web resources and search the configured provider. (fetch, search)"], :result result/e25b2eb43877e, :ms 363}

;; I should know my identity, namespace, and its steward.
my.agents.juniper=> (my.agent/identity)
#:seon.repl{:value #:my.agent{:id "juniper", :namespace my.agents.juniper, :steward "juniper"}, :result result/ed2ebde8e1d8f, :ms 65}

;; I should follow my plan and verify the current step's completion criterion.
my.agents.juniper=> (my.plan/items)
#:seon.repl{:value [{:my.plan.item/id "juniper/query", :my.plan.item/title "Query the orders",
    :my.plan/done-when "I have read the order ids, customers, and amounts.",
    :my.plan/needs [], :my.plan/state :current} {:my.plan.item/id "juniper/aggregate",
    :my.plan.item/title "Find the customer with the largest total", :my.plan/done-when
    "A grouped sum query identifies the customer and their total.", :my.plan/needs
    [#:my.plan.item{:id "juniper/query"}], :my.plan/state :blocked} {:my.plan.item/id
    "juniper/transact", :my.plan.item/title "Add an order of 40 for that customer",
    :my.plan/done-when "The transaction result identifies the new order.",
    :my.plan/needs [#:my.plan.item{:id "juniper/aggregate"}], :my.plan/state
    :blocked} {:my.plan.item/id "juniper/requery", :my.plan.item/title "Read the customer's new total",
    :my.plan/done-when "A fresh grouped sum query includes the new order.",
    :my.plan/needs [#:my.plan.item{:id "juniper/transact"}], :my.plan/state
    :blocked} {:my.plan.item/id "juniper/reply", :my.plan.item/title "Tell root the customer and new total",
    :my.plan/done-when "The sent message contains the customer and verified new total.",
    :my.plan/needs [#:my.plan.item{:id "juniper/requery"}], :my.plan/state
    :blocked} {:my.plan.item/id "juniper/done", :my.plan.item/title "Finish the session",
    :my.plan/done-when "All preceding plan items are complete.", :my.plan/needs
    [#:my.plan.item{:id "juniper/reply"}], :my.plan/state :blocked}], :result result/ef845d902efe4, :ms 71}

;; I should check my inbox for anything I need to respond to.
my.agents.juniper=> (my.message/inbox)
#:seon.repl{:value [#:my.message{:at #inst "2026-09-09T12:00:00.000-00:00", :content "Which customer has the largest total? Add an order of 40 for them and tell me the new total.",
    :from "root", :id "juniper/largest-customer"}], :result result/eb983e404a3c3, :ms 61}

;; I should check my overrides and how many turns I have left.
my.agents.juniper=> (my.agent/settings)
#:seon.repl{:value {:my.agent/turns-left 20, :seon.config.ai/no-provider true, :seon.config.eval/time-limit-ms
  10000, :seon.config.run/max-episode-runs 20}, :result result/ed99a007c24a1, :ms 65}

;; What data is in my namespace?
my.agents.juniper=> (seon.db/q (quote [:find ?attribute (count ?entity) :in $ [?attribute ...] :where [?entity ?attribute _]]) [:example/amount :example/customer :example/order])
#:seon.repl{:value [[:example/amount 4] [:example/customer 4] [:example/order 4]], :result result/ed85aa589da77, :ms 57}
```

Final slice-7 path-limited gate: **2 tests / 98 assertions / zero failures
or errors**, covering `seon.help-trial-test` and `seon.loop-proof-test`.
Separate `--platform`: **83 tests / 490 assertions / zero failures or
errors**. Both used `SEON_TEST_WORKERS=1`. A read-only call of the revised
preflight returned the clean 5,921-byte default prompt; recomputing the
saved reply's score reproduced 10/12 with no additional provider call.
A subsequent default capture matched every byte of the clean capture.
The owned scratch cluster was downed and its holderless root removed;
the two retained failed gate roots from earlier slices were also removed
only after their subjects passed and no process held those roots.

## 14:50 resume — slice 1: one opening generator

Read both assigned blocker issues and PRD §18b end to end before editing.
Read the Seon Clojure, REPL, testing, Datahike, web-rendering and Flow skills.
The initial default capture had six stored evaluations with evidence counts
`[4 1 4 3 6 1]`; the later 9,757-byte capture included an appended help read,
a changed inbox containing a turn-backstop notice, and `(+ 1 1)`. Thus the
claim that every stored opening lacked evidence was falsified for this
capture. The source attribute is still `:seon.cluster.eval/read-evidence`.

Dependency ledger: `seon.turn/evaluate-sources` binds the existing
`seon.db/*read-evidence-sink*`; `seon.db/read-evidence` projects dependency
plans and revisions from Datahike's evidence path; `record-evaluated-call`
and `receipt-settle-call` persist those through the same component writer.
The SCI owner is `reference-code/sci/src/sci/core.cljc` (`fork`/`intern`);
Datahike's serial transaction owner is
`reference-code/datahike/src/datahike/db/transaction.cljc` (`:db.fn/call`).
No evaluator, cache, scheduler or result serialization was added.

The missing production path was agent creation: `ensure-entity!` opens the
bootstrap turn, whose separate legacy `bootstrap/next-entry` generator
closed it with zero evaluations. The new real-graph regression failed two
assertions before repair. `generate-turn` now obtains its forms from
`declared-sources`, just like `system-turn`, then enters the existing
`resume-turn` → `evaluate-sources` → settlement path. Fresh creation stores
help, identity and inbox with evidence counts `[20 16 18]`; a following
system pass appends nothing. The order fixture stores all six reads; its
first ordinary wake retains each opening source exactly once, and the
later single-read change appends only the inbox.

Path-limited gate: **1 test / 98 assertions / zero failures or errors**.
No schema change and no reset required for this slice. The separately
reported cold scratch publication boundary is
[the blank-commit issue](../../../seon/issues/cold-publication-returns-a-blank-commit-without-publishing.md).
An initialization returned zero with no commit, then scratch boot refused
its absent current-src branch and MCP reported a stale advertisement.
The same selected source publishes and boots the isolated gate's base.
No default lifecycle operation was performed.

Slice-1 platform gate: **83 tests / 490 assertions / zero failures or
errors**, with one worker. The selected snapshot included the generator
and real-graph regression changes. Earlier scratch initialization failure
remains explicitly separate from this gate's successful published-base boot.

## 14:50 resume — slice 2: prompt data and truthful tools

RESET NEEDED: `my.run` is renamed to the PRD §5 name `my.turn`, including
its request/result keys, schema resource, readers and tests. The system
owner `seon.run` still constructs the same disposition values. A new
`:seon.fn/internal?` declaration excludes presentation helpers from the
Tools summary without changing callability; static indexing and authored
function admission both preserve that declaration. The namespace docstring
now describes completion and waiting without claiming that every reply must
end with either. Tools remains a program-graph query.

The value renderer always disables table inference, including a supplied
`:table? true` or `:derived`; standalone `seon.print` retains its table
feature. Removed the obsolete `:tabular` profile option. Plan `:needs`
projections return item ids while the stored dependency edges remain refs.
The virtual/no-provider fallback reply is empty. Real explicitly submitted
forms still evaluate. Help has no revision triangles; its plan and message
examples now use the actual declared request keys.

A fresh scratch boot exposed an additional race that the earlier isolated
fixture did not exercise: a system pass evaluated against pre-compaction
history, then appended five reads after that history had been cleared.
The following opening was help plus the same five reads. The serial
transaction writer now checks that the history from which an append was
derived is still current. A deterministic regression interleaves two real
system passes through real SCI and the canonical database; only one append
commits. No timing field is stripped and no history survives compaction.
The subsequent live reseed has exactly six evaluations, help first.

The scratch CLI cold-publication boundary remains open. The gate's complete
published base was copied into the stopped, owned scratch root and
`seon.cluster.export/reidentify!` adjusted the copied store's identity,
exactly as the canonical file-backed fixture does. Fresh boot then reached
web and MCP readiness on port 7833. An earlier publisher call through the
default JVM remained within that JVM's process root; it did not initialize
the scratch root. The final history-commit guard was hot-reloaded into the
scratch JVM for this live reseed. Default was never stopped or reforked.

I read the following scratch prompt top to bottom. It is 5803 bytes, SHA-256 `6a81378d6e49b711f0aca05e6e00580780a6204365a5abc5e76d8264d26ef24c`.

```clojure
;; I should understand how this REPL works before I act.
my.agents.juniper=> (help)
#:seon.repl{:value ["You are at a Clojure REPL in your namespace my.agents.juniper. Every function in the program is callable."
  "Reply with ;; thinking comments, each followed by the form it plans. Send only comments and forms; the prompt my.agents.juniper=> is drawn for you."
  "Forms are evaluated in order, and their results arrive in your NEXT turn. Act on a result only after you have seen it; do not complete a step in the same reply as the form that does the work."
  "Each form returns one #:seon.repl map: :value (or :error) is data, :out is anything printed, :result names the live value."
  "result/e... is a real symbol bound to the live value: evaluate it, pass it as an argument, or dig in with get-in and keys."
  "When unsure how to call something, ask first: (dir my.plan) lists a namespace's functions as data; (doc seon.db/q) returns a docstring and contract as data."
  "Your plan is your instructions: (my.plan/items). The current step's :done-when says what done means. (my.plan/complete! {:my.plan.item/id id}) when it is."
  "(my.message/inbox) is what you were sent. (my.message/send {:my.message/to \"root\" :my.message/content \"...\"}) sends. Sending a message does not end your turn."
  "(seon.db/q '[:find ...]) queries, (seon.db/pull '[*] eid) reads one entity, (seon.db/transact! [{...}]) writes. The database is your cluster's and is supplied for you."
  "A defn with :malli/schema becomes a durable function. A deftest becomes a durable test. (my.test/run) runs yours."
  "A mistake returns :error data, never an exception. Read :seon.error/message and try again."
  "Each reply is one turn. :turns-left in your settings counts down. (my.agent/done) ends your session early."
  "Tools: my.agent — Read and update my record through request maps. (done, identity, settings, settings!); my.background — Start and inspect capability requests that may finish later. (await, background, poll); my.edit — Edit source files only when their expected digest still matches. (exact, form, lines); my.fs — Read, write, inspect, and find files with bounded results. (glob, read, stat, write); my.message — The inter-agent message protocol, with optional request-map calls; call preparation supplies my database and identity. (decline, inbox, read, send); my.note — My durable notes through one request map per call. (add!, forget!, notes); my.plan — The calling agent’s plan protocol. Each operation takes one request map. (add!, blocked, complete!, current, current!, item, items, plan, ready, ready-subjects, steps, update!); my.shell — Bounded foreground argv-vector process requests. (run); my.test — Run the tests declared in my namespace. (run); my.turn — Return explicit completion or waiting data for my session. (complete, wait); my.web — Fetch web resources and search the configured provider. (fetch, search)"], :result result/e3b1c11b89513, :ms 585}

;; I should know my identity, namespace, and its steward.
my.agents.juniper=> (my.agent/identity)
#:seon.repl{:value #:my.agent{:id "juniper", :namespace my.agents.juniper, :steward "juniper"}, :result result/ed25ec5adc95c, :ms 74}

;; I should follow my plan and verify the current step's completion criterion.
my.agents.juniper=> (my.plan/items)
#:seon.repl{:value [{:my.plan.item/id "juniper/query", :my.plan.item/title "Query the orders",
    :my.plan/done-when "I have read the order ids, customers, and amounts.",
    :my.plan/needs [], :my.plan/state :current} {:my.plan.item/id "juniper/aggregate",
    :my.plan.item/title "Find the customer with the largest total", :my.plan/done-when
    "A grouped sum query identifies the customer and their total.", :my.plan/needs
    ["juniper/query"], :my.plan/state :blocked} {:my.plan.item/id "juniper/transact",
    :my.plan.item/title "Add an order of 40 for that customer", :my.plan/done-when
    "The transaction result identifies the new order.", :my.plan/needs ["juniper/aggregate"],
    :my.plan/state :blocked} {:my.plan.item/id "juniper/requery", :my.plan.item/title
    "Read the customer's new total", :my.plan/done-when "A fresh grouped sum query includes the new order.",
    :my.plan/needs ["juniper/transact"], :my.plan/state :blocked} {:my.plan.item/id
    "juniper/reply", :my.plan.item/title "Tell root the customer and new total",
    :my.plan/done-when "The sent message contains the customer and verified new total.",
    :my.plan/needs ["juniper/requery"], :my.plan/state :blocked} {:my.plan.item/id
    "juniper/done", :my.plan.item/title "Finish the session", :my.plan/done-when
    "All preceding plan items are complete.", :my.plan/needs ["juniper/reply"],
    :my.plan/state :blocked}], :result result/e301a93f8da98, :ms 87}

;; I should check my inbox for anything I need to respond to.
my.agents.juniper=> (my.message/inbox)
#:seon.repl{:value [#:my.message{:at #inst "2026-09-09T12:00:00.000-00:00", :content "Which customer has the largest total? Add an order of 40 for them and tell me the new total.",
    :from "root", :id "juniper/largest-customer"}], :result result/e77f0a99b3d48, :ms 82}

;; I should check my overrides and how many turns I have left.
my.agents.juniper=> (my.agent/settings)
#:seon.repl{:value {:my.agent/turns-left 20, :seon.config.ai/no-provider true, :seon.config.eval/time-limit-ms
  10000, :seon.config.run/max-episode-runs 20}, :result result/e7530319b29d2, :ms 76}

;; What data is in my namespace?
my.agents.juniper=> (seon.db/q (quote [:find ?attribute (count ?entity) :in $ [?attribute ...] :where [?entity ?attribute _]]) [:example/amount :example/customer :example/order])
#:seon.repl{:value [[:example/amount 4] [:example/customer 4] [:example/order 4]], :result result/ed308a58c8319, :ms 73}
```

Slice-2 final path-limited gate: **51 tests / 417 assertions / zero failures
or errors**, one worker. The additional interleaving check is included;
the earlier fast check was 2 tests / 131 assertions. The initial platform
pass was 83 tests / 490 assertions; a second platform pass covers the final
history-commit guard before this slice is reported.

The final separate platform pass is **83 tests / 490 assertions / zero
failures or errors**. Default adoption refused at schema population:
`:seon.schema/generatable?` was required but absent from a submitted schema
row. This is the exact publication boundary, not an attribution to another
lane. The selected slice's canonical publication and fresh boot succeeded.
After that refused adoption, default's debug page still returned HTTP 200
(91,740 HTML bytes). Its historical prompt was not reseeded in this slice.
RESET NEEDED remains recorded for this commit; the orchestrator owns the
single default refork. No provider call was made.

## 14:50 resume — slice 3: predicate publication regression

The requested full sequence is now a committed canonical regression:
publish the old registered predicate and a schema that names it; publish
the renamed source through `seon.cluster.source/upsert!` and `seon.fn/index!`;
retain the old identity; remove its temporary host namespace; fork and boot
a fresh cluster. Both projections come from the actual published database.
The old identity has no definition facts and no SCI callable; the new
`seon.shell/stdin?` does resolve. Fast result: **1 test / 11 assertions /
zero failures or errors**.

The proposed tombstone cause was falsified for the current implementation.
`seon.schema/derive-projection-from-database` already joins current schema
forms and function contracts, and `seon.fn/reconcile-tx` retracts the old
source/spec/namespace facts while retaining the identity. No production
filter was added to conceal a live schema's invalid predicate reference.
The original refused publication's offending row was not retained before
its complete republication; its cause remains explicitly unattributed in
the issue. This slice adds the previously missing full regression, not a
claim that the original failure was reproduced. No schema change or new
reset is required here; RESET NEEDED from `e915d2de0` remains outstanding.

Slice-3 isolated gate: **1 test / 11 assertions / zero failures or errors**;
the full publication/rename/boot test took **118,903 ms**. Separate platform:
**83 tests / 490 assertions / zero failures or errors**. Both used one worker.
The default debug page remained HTTP 200. Its stored history still contains
the earlier arithmetic placeholder and prior help values, pending the final
fixture reseed; this test-only slice did not rewrite those historical bytes.
