---
type: issue
status: open
severity: friction
created: 2026-09-23
tags: [contracts, schema-shape, call-preparation, wave/publication-velocity]
---

# Default eligibility previously matched differently named schema aliases

The owner's named-reference ruling removes structural default inference.
A fresh canonical scratch publication (`6ab09443-7b2d-55ed-91e7-39c3e61726d8`)
contains 26 argument slots in 23 functions that formerly matched a supplied
default through an equivalent but differently named schema. The reproducible
query/compiled-contract audit is
`seon.fn.schema-shape-test/inline-default-contracts`.
It found **zero inline copies** and the aliases below. None is in the lane's
owned source paths. The list is a dated observation, not a maintained roster.

Twenty-one functions (23 slots) declare `:seon.db/db`, whereas the supplied
schema is `:seon.db/database-value`. If they intend database omission, their
contracts must declare the latter name. Explicit callers remain valid.
`seon.cluster.message/send` (two arities) and `decline` declare
`:my.message/to`, structurally equal to `:seon.agent/id`: removal of automatic
self-recipient inference is intentional; do not convert recipients blindly.
This audit proves former eligibility, not that every function intends omission.

| Function | Declaration | Authored name |
|---|---|---|
| `seon.agent/archived?` | `src/seon/agent.clj:33` | `:seon.db/db` |
| `seon.agent/effective-settings` | `src/seon/agent.clj:117` | `:seon.db/db` |
| `seon.agent/identity` | `src/seon/agent.clj:11` | `:seon.db/db` |
| `seon.agent/open?` | `src/seon/agent.clj:47` | `:seon.db/db` |
| `seon.agent/settings` | `src/seon/agent.clj:75` | `:seon.db/db` |
| `seon.ai/agent-setting-attributes` | `src/seon/ai.clj:325` | `:seon.db/db` |
| `seon.bootstrap/beyond-closure-budget` | `src/seon/bootstrap.clj:379` | `:seon.db/db` |
| `seon.bootstrap/help-value` | `src/seon/bootstrap.clj:22` | `:seon.db/db` |
| `seon.bootstrap/situation` | `src/seon/bootstrap.clj:103` | `:seon.db/db` |
| `seon.cluster.message/decline` | `src/seon/cluster/message.clj:692` | `:my.message/to` |
| `seon.cluster.message/send` | `src/seon/cluster/message.clj:627` | `:my.message/to` |
| `seon.eval/of-agent` | `src/seon/eval.clj:9` | `:seon.db/db` |
| `seon.plan/blocked` | `src/seon/plan.clj:552` | `:seon.db/db` |
| `seon.plan/current` | `src/seon/plan.clj:536` | `:seon.db/db` |
| `seon.plan/ready` | `src/seon/plan.clj:574` | `:seon.db/db` |
| `seon.plan/ready-subjects` | `src/seon/plan.clj:586` | `:seon.db/db` |
| `seon.plan/steps` | `src/seon/plan.clj:563` | `:seon.db/db` |
| `seon.render.data/entity-observation` | `src/seon/render/data.clj:204` | `:seon.db/db` |
| `seon.render.value/transacted` | `src/seon/render/value.clj:30` | `:seon.db/db` |
| `seon.repl/frame` | `src/seon/repl.clj:50` | `:seon.db/db` |
| `seon.sci.eval/directory-value` | `src/seon/sci/eval.clj:1565` | `:seon.db/db` |
| `seon.sci.eval/documentation-value` | `src/seon/sci/eval.clj:1597` | `:seon.db/db` |
| `seon.turn/turns-left` | `src/seon/turn.clj:2742` | `:seon.db/db` |

Acceptance: review intended omission at these declarations; convert contracts
that request a default to its exact declared schema name, and retain explicit
aliases where omission is not intended. Verify on the canonical armed
fixture. Do not reintroduce shape-equivalence matching or a second identity.

Evidence and stored-size measurements:
[landing note](../../prds/steward-platform/research/schema-shape-authored-2026-09-23.md).

## Live omission repair — 2026-09-21

Fresh default evaluations after publication/adoption
`6ab18937-ff35-559a-a665-022e153f3027` recorded zero-argument refusals for
`(help)` (evaluation entity 37541, `seon.bootstrap/help-value`) and
`(seon.agent/settings)` (entity 39293). The owner queried the exact stored
source and error in 5 ms. A read-only context probe confirmed both functions
in the preparation snapshot's eligible-symbol set; that alone is insufficient
because eligibility can come from only one argument. The owner's follow-up
plan probe (487 ms; basis 536870918, contract transaction 536870917) showed
only index 1, `:seon.agent/id`, supplied, and accepted counts 1 and 2, never 0.
Both contracts still named `:seon.db/db` at index 0. The SCI wrapper and
supplied-default mechanism were operating as declared.

The repair changes the database input name to `:seon.db/database-value` on
twelve reads intended to address the calling agent: `help-value`; the five
own-record reads in `seon.agent` (`identity`, `archived?`, `open?`, `settings`,
`effective-settings`); the five own-plan reads in `seon.plan` (`current`,
`blocked`, `steps`, `ready`, `ready-subjects`); and `seon.turn/turns-left`.
The help macro emits a zero-argument call, settings rendering emits
`(seon.agent/settings)` and advertises `(seon.agent/effective-settings)`,
and help advertises `(seon.turn/turns-left)`. The agent and plan read families
address the calling agent's own state. Explicit database and agent arguments
remain admitted with the same implementation and result contract.

The remaining inventory is deliberately unchanged: bootstrap generation
helpers, AI attribute discovery, evaluation-history queries, frame construction,
and documentation helpers have explicit database callers; doc/dir macros
already emit `(seon.db/db)`. Render entity/transformation helpers also retain
their authored input semantics. Message recipients remain explicit; structural
alias matching is not restored.

The canonical SCI regression
`seon.sci.supplied-database-test/own-record-reads-supply-the-same-database-and-agent-as-explicit-calls`
compares omitted and explicit calls for all twelve reads, rejects error values,
and requires actual help content. Verification is pending the owner's serial
gate and fresh live evaluations; the bounded lane ran no test, JVM, operator,
or adoption command. Stored historical evaluation errors remain historical
facts until reset; successful new evaluations are the live proof.
