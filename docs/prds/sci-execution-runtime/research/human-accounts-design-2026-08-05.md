---
type: research
status: proposed
tags: [research, schema, database, messaging, render, runtime]
---

# Human accounts and escalation delivery design — 2026-08-05

## Verdict

This iteration recommends one ordinary human entity per cluster, identified in
v1 by `:seon.human/email`. The same person in two sovereign cluster branches is
two entities carrying the same email value, not a shared entity or a
cross-branch ref. A cluster declares zero or more escalation recipients through
ordinary refs from its cluster entity, and each human declares the agents whose
namespace pages should show that human's inbox.

An escalation remains an ordinary `:seon.cluster.message/message`. Each message
has one `:seon.cluster.message/to` ref to one human entity; sending to three
humans makes three message rows in one terminal transaction. The existing
agent-only `:my.message/to` contract should not change meaning. A new
`:my.message/to-human` key and `my.message/send-human` constructor should
accrete a distinct addressing arm that resolves a human by email and produces
the same durable message shape.

The operations path remains two-stage. A maintenance failure or core fault
uses the existing error path to message root. Root classifies the facts, then
returns one or more ordinary human-message values. There is no direct
maintenance-to-human notification path and no new notification queue, proc,
channel, route, acknowledgement flag, or stored inbox projection.

In v1, the human entity's declared HTML producer derives its inbox by querying
messages addressed to that entity. A ref from the human to a watched agent puts
the human in the existing agent-rooted render walk, so the inbox appears as an
ordinary block on `/`, `/ns/{namespace}`, or their agent aliases and updates
through the existing commit → render wake → equality-suppressed block morph
path. Its AI producer returns nil so a human-facing inbox does not silently
enter the watched agent's prompt.

This is an iteration draft for owner review, not a sealed specification. It
makes no production change.

## Scope and authorities read

I read the repository-root `AGENTS.md` end to end, including its data, schema,
render, flow, vocabulary, testing, and documentation rules. I also read the
closest localized authority,
[docs/prds/sci-execution-runtime/AGENTS.md](../AGENTS.md), end to end.

The named planning authorities were read end to end rather than searched:

- [docs/prds/sci-execution-runtime/plan/operations-and-maintenance-spec-2026-08-05.md](../plan/operations-and-maintenance-spec-2026-08-05.md)
- [docs/prds/sci-execution-runtime/plan/state-of-the-program-2026-08-05.md](../plan/state-of-the-program-2026-08-05.md)
- [docs/prds/sci-execution-runtime/plan/unsettled.md](../plan/unsettled.md)

The relevant current architecture documents were also read:

- [docs/seon/architecture/data-model.md](../../../seon/architecture/data-model.md)
- [docs/seon/architecture/ui.md](../../../seon/architecture/ui.md)
- [docs/seon/architecture/observability.md](../../../seon/architecture/observability.md)

The source grounding covered the complete message owner and schema, the web
submission and page paths, the route table, render walk, wake router, error
committer, cluster and agent schemas, `seon.db` transaction boundary, and the
relevant Datahike transaction implementation. `bin/seon status` returned no
live cluster listing, so this research makes source-grounded claims only and
does not claim a live page observation.

## Dependency ledger

The first-party tree was read at commit
`700b0f65b90e2c4d60c64433e2f6996f1a24f062`. Concurrent unrelated work in
`src/seon/operator.clj` and two untracked research scripts was present and was
not touched.

| Dependency or owner | Selected revision | Grounding used here |
|---|---|---|
| Datahike | maintained local fork at `56f1c62105b7` (`v0.3.2-868-g56f1c621`) via `deps.edn:24-25` | Argument-map transactions accept `:tx-data` and `:tx-meta` (`reference-code/datahike/src/datahike/api/impl.cljc:30-48`); transaction metadata is expanded into datoms on the transaction entity (`reference-code/datahike/src/datahike/db/transaction.cljc:902-921,1214-1237`); ref values are resolved with `entid-strict` (`transaction.cljc:785-810`); lookup refs require a unique attribute and an already-resolvable value (`reference-code/datahike/src/datahike/db/utils.cljc:109-145`). |
| Malli and Seon's schema bridge | Malli `0.20.0`, `deps.edn:14`; current `seon.schema.edn` population | Existing entity declarations in `resources/seon/schemas/seon.cluster.message.edn:48-77`, `seon.cluster.edn:1-18`, and `seon.cluster.agent.edn:1-14` establish the open-map, attributes-plus-connections shape. |
| core.async Flow and Seon's wake owner | core.async `1.10.874-alpha3`, `deps.edn:20-22`; current `seon.cluster.wake` | Message recipient datoms route agent work by recipient eid, while every transaction report independently wakes rendering (`src/seon/cluster/wake.clj:163-245`). This design adds no proc, graph, channel, or buffer. |
| Reitit route owner | `metosin/reitit-ring` `0.10.1`, `deps.edn:95` | The one first-party route table is the authority (`src/seon/render/route.clj:5-34`). No new route or dependency behavior is proposed. The checkout also contains an unselected `reference-code/reitit` revision, so it was not treated as the selected runtime coordinate. |
| Current first-party idioms | current source commit above | One-message-per-recipient vectors (`src/my/message.clj:44-59`; `src/seon/cluster/message.clj:306-423`), transaction provenance at the web boundary (`src/seon/render/web.clj:1123-1156`), declared dual render producers (`resources/seon/schemas/seon.cluster.message.edn:48-77`), and one shared walk/page pipeline (`src/seon/render/walk.clj:182-206,280-305`; `src/seon/render/web.clj:306-375`). |

## What exists now

### A message is already structurally entity-addressed

The durable message schema requires one `:seon.cluster.message/to` ref, not an
agent-typed ref (`resources/seon/schemas/seon.cluster.message.edn:48-85`). It
optionally carries a `:seon.cluster.message/from` ref, plus content, time,
ordinal, causation, subject, and reason. That means the durable row needs no
parallel human-message entity and no recipient taxonomy.

The current constructors are narrower than the stored shape:

- An inbound web POST creates one message to an agent lookup ref and deliberately
  omits `:seon.cluster.message/from` (`src/seon/cluster/message.clj:258-304`).
- `my.message/send` says it addresses another agent and takes an agent id string
  (`src/my/message.clj:17-59`; `resources/seon/schemas/my.message.edn:1-21`).
- Run-loop delivery verifies that id against `:seon.cluster.agent/id`, then
  writes one row per candidate with agent lookup refs for both `/to` and `/from`
  (`src/seon/cluster/message.clj:306-423`).
- A vector is already the multi-recipient form. Deliverable candidates commit
  while unknown recipients become flat errors; the function does not make one
  cardinality-many recipient row (`message.clj:306-311,368-423`).

The right change is therefore a new resolver arm into the existing message
shape, not a second message family.

### Outside origin and transaction authorship are different facts

The current inbound contract is explicit: absence of
`:seon.cluster.message/from` means “outside the agent population,” and
provenance belongs on the transaction (`src/seon/cluster/message.clj:258-269`).
`sender` and automatic reply intentionally recognize only an agent `/from`
(`message.clj:121-150`). A human-origin message consequently opens a new
conversation chain without pretending that the human is an agent; chain depth
already describes a human or error-recorder origin as depth zero
(`message.clj:97-119`).

`seon.db` does not resolve or invent `:seon.db/user`. It passes the caller's
Datahike argument map through the one transaction owner. Datahike expands
`:tx-meta` into datoms on the transaction entity, and because
`:seon.db/user` is a ref (`resources/seon/schemas/seon.db.edn:149`), Datahike
resolves the supplied lookup ref against the database value at the transaction
basis. The referenced human must therefore already exist; creating the human
and using its email lookup ref in the same transaction metadata is not a valid
assumption.

The sole current production writer of `:seon.db/user` is the web submission
boundary. It receives the destination agent id and records that recipient as
the transaction user (`src/seon/render/web.clj:1123-1156`). That is false
authorship: the agent being messaged did not submit the POST.

### Rendering already has the inbox delivery transport

Canonical namespace routes resolve to their owner agent and use the same page
pipeline as agent aliases. The complete route table contains `/`, namespace
and agent pages, their debug variants, the agent-message POST, the agent feed,
data, and static assets; it contains no viewer or account route
(`src/seon/render/route.clj:5-27`).

An agent page derives the shared neighborhood from the agent entity at depth
two (`src/seon/render/web.clj:1243-1267`). The walk follows every installed
forward and reverse ref for ordinary entities (`src/seon/render/walk.clj:182-206`),
and the same flat units feed AI and HTML projections (`walk.clj:280-305`). A
human's cardinality-many ref to a watched agent is therefore visible in reverse
from that agent in one hop. The human entity can render an inbox block without
adding a route or a page-specific renderer.

Every transaction report already wakes the render path before selective agent
routing, and the page pipeline suppresses equal block bytes
(`src/seon/cluster/wake.clj:180-186,207-234`; `src/seon/render/web.clj:306-375`).
A human-addressed message is durable before the wake, a dropped/coalesced wake
is harmless, and reconnect repaints from facts.

There is one current runtime wrinkle. `:seon.cluster.message/to` is also a
selective agent-work wake. The router looks up the recipient eid in the armed
agent routing map; an unknown eid offers one wake to the armer
(`src/seon/cluster/wake.clj:229-234`). A human has no agent route, so the
message causes a harmless armer pass and no model turn. V1 can accept that
small redundant wake. If human traffic later makes it material, the existing
router should derive whether the recipient is an agent at an appropriate
precomputed boundary; a second `/to-human` durable attribute or human-delivery
channel would violate the one-message and one-render path.

### P5 is exactly the missing second stage

The program ledger says root can classify legitimate disk pressure but cannot
address a human
([docs/prds/sci-execution-runtime/plan/state-of-the-program-2026-08-05.md](../plan/state-of-the-program-2026-08-05.md),
P5 at lines 187-190). The operations specification deliberately leaves this
recipient contract open while retaining root judgment
([docs/prds/sci-execution-runtime/plan/operations-and-maintenance-spec-2026-08-05.md](../plan/operations-and-maintenance-spec-2026-08-05.md),
lines 71-84).

The error owner already commits an error fact and its explanatory messages in
one transaction. Those messages resolve an agent recipient and use ordinary
`:seon.cluster.message/to`, which is the existing wake
(`src/seon/error.clj:712-746,815-859`). Handler failures in the operations spec
therefore still message root and wake a real root turn. Human escalation fills
what root may return after that turn; it should not rewrite the error recorder.

## Proposed data model

This EDN is a shape sketch, not sealed syntax:

```clojure
#:seon.human
{:email [:string {:min 1 :seon.db/identity true}]
 :name [:string {:min 1}]
 :watched-agents [:set :seon.db/ref]
 :human
 [:map
  {:seon.db/entity true
   :seon.render/ai seon.human/render-ai
   :seon.render/html seon.human/render-inbox-html}
  [:seon.human/email :seon.human/email]
  [:seon.human/name {:optional true} :seon.human/name]
  [:seon.human/watched-agents
   {:optional true}
   :seon.human/watched-agents]]}
```

The existing cluster registry would accrete one attribute and the cluster
entity would admit it as an optional connection:

```clojure
#:seon.cluster
{:escalation-humans [:set :seon.db/ref]
 :cluster
 [:map
  ;; existing required cluster attributes remain unchanged
  [:seon.cluster/escalation-humans
   {:optional true}
   :seon.cluster/escalation-humans]]}
```

The entity is discovered by attribute presence. There is no `:type`, `:kind`,
role stamp, account table, membership row, stored inbox, unread count, seen
flag, or notification state. All maps stay open. Email, display name, watched
agents, and escalation policy are facts; inbox contents and counts are queries.

The proposed refs have distinct meanings:

- `:seon.human/watched-agents` is presentation placement: show this human's
  inbox block on pages rooted at those agents.
- `:seon.cluster/escalation-humans` is policy: these humans are eligible
  recipients for root escalation in this cluster.

Neither implies the other. A human may watch a page without receiving root
escalations, or receive an escalation whose inbox is rendered on a different
watched page. A configuration helper may set both, but the database facts must
not silently derive one meaning from the other.

No push-provider ref is proposed in v1. When a real provider-row contract is
designed, the human may accrete a cardinality-many provider connection. A
settled human message can then produce a genuine capability request through
`seon.effect`, with the ordinary message and effect receipt remaining the
durable facts. Email as human identity does not mean email delivery is already
configured.

## Identity across sovereign clusters

Recommended v1 semantics:

1. `:seon.human/email` is a unique identity attribute inside each cluster.
2. Cluster A's `[:seon.human/email "person@example.com"]` and cluster B's
   identical lookup ref resolve to two independent entities on two independent
   branches.
3. “Same person” across clusters means the same explicit identity value was
   admitted to each branch. No entity id, ref, branch head, watch selection,
   or escalation policy crosses the branch.
4. An operator or future account provisioner may explicitly upsert that row to
   several clusters. Startup never synchronizes it and one cluster's email or
   display-name edit never migrates another cluster.

V1 should validate only a non-blank string. Email syntax validation,
canonicalization, ownership, authentication, and address verification are out
of scope. A regex or provider-specific normalization would pretend those
contracts exist. Exact stored strings are the identity, so the provisioning
boundary must choose and consistently reuse the form it wants.

An email change is an explicit identity edit in every affected cluster. The
entity's existing refs and historical transaction-user refs remain attached to
its eid within that branch, but cross-cluster matching by the old email stops.
If durable rename-independent cross-install identity becomes a demonstrated
need, a new immutable identity attribute can accrete later; the meaning of
`:seon.human/email` must not change.

## Provenance tie-in

`:seon.db/user` should mean the entity whose action requested the transaction,
whether that entity is a human or an agent. `:seon.db/process` continues to
name the process that performed it. These are independent provenance facts.

For a human-authored inbound message:

```clojure
{:tx-data [[:db.fn/call #'seon.cluster.message/inbound-tx request]]
 :tx-meta {:seon.db/process [:seon.db.process/id process-id]
           :seon.db/user [:seon.human/email human-email]}}
```

The account row must pre-exist because Datahike resolves the lookup ref against
the current basis before the content transaction. A missing or ambiguous
resolved human is a boundary refusal, not a reason to stamp the recipient agent
or create an account implicitly.

Authentication is out of scope, so v1 cannot accept an arbitrary form field,
header, or query parameter containing an email and call it provenance. Until
an upstream request context has already resolved a human entity, the honest web
submission metadata is only `:seon.db/process`; `:seon.db/user` is absent. The
current destination-agent stamp should be removed when this work is
implemented because known-false provenance is worse than absent provenance.

The inbound message should continue to omit `:seon.cluster.message/from`.
That ref means an agent domain sender and drives automatic agent reply. A
human author's display identity can be derived from the transaction that
asserted the message's current content by joining that transaction to
`:seon.db/user`. This keeps one authorship fact and preserves the existing
outside-agent chain and reply semantics. Agent-authored outbound messages keep
their `/from` ref because it records semantic sender/causation used by replies,
not merely the process that committed the transaction.

## Human addressing and multi-human delivery

The agent result surface should accrete a distinct value arm:

```clojure
{:my.message/to-human "person@example.com"
 :my.message/content "Disk pressure needs an operator decision."}
```

`my.message/send-human` should validate the email and content and return that
ordinary value. Run-loop delivery should require exactly one recognized
address key, resolve `:my.message/to-human` through the unique
`:seon.human/email` attribute, and write the existing durable message row:

```clojure
{:seon.cluster.message/id derived-id
 :seon.cluster.message/to [:seon.human/email "person@example.com"]
 :seon.cluster.message/from [:seon.cluster.agent/id "root"]
 :seon.cluster.message/content content
 :seon.cluster.message/ordinal index
 :seon.cluster.message/at at}
```

Unknown humans produce the same class of flat recipient error as unknown
agents. A value containing both `:my.message/to` and
`:my.message/to-human` is an ambiguous-address error; open maps do not require
silently choosing one declared key over another.

Multiple humans means a vector of single-recipient values and therefore one
message entity per human. Root's convenience function may derive that vector
from `:seon.cluster/escalation-humans`, but it must sort recipients by
`:seon.human/email` before assigning vector indexes: Datahike cardinality-many
is a set, while message ids and ordinals are derived from stable vector order.
This preserves the current partial-delivery and per-recipient error behavior,
makes each recipient independently queryable, and avoids changing the meaning
of cardinality-one `:seon.cluster.message/to`.

## Inbox rendering on watched pages

`seon.human/render-inbox-html` should use the human entity in the render unit
to query messages whose `/to` equals that entity. The block should:

- identify the human by display name when present and email otherwise;
- order messages with the existing transcript law: message time, assertion
  transaction, ordinal, then entity id (`src/seon/render/transcript.clj:155-167`),
  with one explicitly chosen oldest-first or newest-first presentation;
- render each message through the existing message projection rather than
  rebuilding its text or Hiccup;
- apply the selected render profile and ordinary elision values rather than a
  new literal inbox cap; and
- return nil when no message is addressed to that human, so an empty inbox
  produces no block.

The human entity's `seon.human/render-ai` should return nil in v1. The reverse
watch ref exists to place a human-facing block on a page, not to put account
facts or an inbox into an agent's model context. If agents later need to know
who watches them, that should be an explicitly designed AI projection with a
measured context benefit, not an accidental consequence of the shared walk.

Because the current web UI has no viewer session or human identity, v1 cannot
privately select “my” inbox. Every human entity configured to watch the page is
reachable and its inbox block is visible to anyone who can open that page.
That is an honest installation-local operational surface, not an authenticated
account portal. Adding a cookie, query parameter, or unverified email selector
would not create privacy. Authenticated per-viewer selection remains out of
scope and must later strengthen the existing page request context rather than
forking the renderer.

Watching an agent is recommended over watching a namespace in v1. Canonical
namespace routes already resolve the namespace's owner agent before walking,
while namespace entities deliberately traverse only `:seon.ns/requires`
connections (`src/seon/render/walk.clj:228-247`). The trade-off is explicit:
the watch follows the agent, not a namespace reassigned to a different agent.
If namespace-following semantics are required, that is a separate owner
decision about the walk or watch relation; storing both refs as if they meant
the same thing would be ambiguous.

## Root escalation after P5

The recommended fact and execution sequence is:

```text
maintenance handler failure or core fault
  -> existing error fact + ordinary message to root
  -> existing recipient wake opens root turn
  -> root queries the maintenance/error facts and classifies the condition
  -> root queries this cluster's :seon.cluster/escalation-humans refs
  -> root returns sorted my.message/send-human values for its chosen recipients
  -> run-loop delivery commits one ordinary message per human
  -> existing render wake re-derives watched pages
  -> each affected human inbox block morphs if its bytes changed
```

Legitimate low disk pressure that is not itself an error becomes input to the
same root judgment surface described by the operations spec. Root decides
whether to send; the footprint observer does not fan out directly. An empty
`:seon.cluster/escalation-humans` set must be visible to root as “no configured
human recipient,” never guessed as a special email, root's web visitor, or the
process operator.

The existing `:seon.config.error/escalate-to` remains an agent id naming root
for the mechanical error path. It should not widen to human identities: direct
error-to-human fanout would bypass root's classification, mix machine and human
recipient semantics in one existing key, and give error recording a second
delivery policy.

Human-addressed messages must not start model runs. Current routing already
has that outcome because only agent eids have mailbox entries. The redundant
armer offer noted above is an efficiency seam, not a correctness dependency
and not justification for a new flow mechanism.

## Open points, options, and recommendations

### Human identity

1. **Email as the v1 unique identity — recommended.** It is already the
   owner's candidate, is usable as a human-facing address, and can be explicitly
   repeated on sovereign branches. Cost: renames are per-cluster identity
   edits, and exact-string normalization belongs to provisioning.
2. Immutable Seon UUID identity plus email as an ordinary indexed attribute.
   This makes renames cheap but requires a new cross-cluster authority to
   distribute the same UUID before auth or account provisioning exists.
3. External-provider subject as identity. This binds core human data to an
   auth provider and violates the current auth boundary; reject for v1.

### Escalation recipient declaration

1. **Cardinality-many `:seon.cluster/escalation-humans` refs — recommended.**
   Policy is queryable on the sovereign cluster and each target is validated by
   an existing entity.
2. Put escalation-human refs on root. This attaches installation policy to the
   current classifier agent and makes a root replacement or delegation alter
   recipient semantics; not recommended.
3. Store email strings in config or assume one owner. Both duplicate an
   unresolved identity and fail the many-human requirement; reject.

### Cross-cluster provisioning

1. **Explicitly upsert the human row into each selected cluster — recommended.**
   An operator function or future account provisioner accepts the target
   branches and the same email value, and each branch commits its own row and
   refs. Partial success is reported per cluster; there is no pretend atomic
   transaction across branches.
2. Put humans in `:current-src` so every fork inherits them. Human accounts are
   installation data, not program-graph initialization rows, and existing
   sovereign clusters would still not update; reject.
3. Create a human implicitly on the first message or page visit. This turns an
   untrusted address string into an account and makes provenance lookup depend
   on an entity that does not yet exist; reject.

### Agent-facing address value

1. **New `:my.message/to-human` plus `my.message/send-human` — recommended.**
   It accretes a distinct meaning and resolves into the existing message row.
2. Reinterpret `:my.message/to` as “agent id or human email.” This silently
   changes an established key's definition, makes collisions ambiguous, and
   changes existing error prose; reject.
3. Accept raw entity ids or lookup refs from agent code. This leaks branch-local
   database representation into the result contract and is harder for humans
   and agents to author; reject.

### Multi-human representation

1. **One message row per human, committed from a sorted vector — recommended.**
   It preserves cardinality-one `/to`, existing delivery semantics, stable ids,
   independent inbox queries, and per-recipient errors.
2. One message with cardinality-many `/to`. Cardinality-many is unordered and
   would change every renderer, wake, sender/reply query, and delivery contract;
   reject.
3. One message to a synthetic group entity. It adds a group-expansion and
   membership-history mechanism that P5 does not need; defer until groups are
   independently required.

### Origin attribution

1. **Keep human origin in transaction `:seon.db/user`; keep message `/from`
   agent-only — recommended.** It preserves outside-agent chain/reply semantics
   and avoids duplicate authorship facts.
2. Also store the human in message `/from`. This changes `sender`, automatic
   reply, and renderer assumptions and duplicates transaction provenance;
   reject for v1.
3. Omit authorship forever because auth is out of scope. Honest absence is
   correct only until a trusted boundary resolves a human; retaining no tie
   after that would discard available provenance.

### Watched-page relation

1. **Human → watched agent refs — recommended for v1.** They enter the current
   agent-rooted walk and work for canonical namespace pages and agent aliases
   without route or walk changes.
2. Human → watched namespace refs. This expresses namespace persistence better,
   but the current namespace walk filters that ref, so it needs a separate walk
   ruling.
3. Agent → watching humans. It also traverses, but makes the watched surface
   own account preference facts and is less natural for editing one human's
   watch list.

### Inbox visibility before auth

1. **Render all configured watched-human inbox blocks and state that the page
   has no per-human privacy in v1 — recommended.** It is honest and uses the
   existing page.
2. Limit each page to one human. This contradicts the many-human requirement
   and stores a constraint only to simulate missing auth; reject.
3. Select by query parameter, form email, or unsigned cookie. This is not
   identity or privacy and would create misleading behavior; reject.

### Inbox ordering

1. **Newest first using the existing total transcript order — recommended.**
   Escalations are operational attention items, and the existing transcript
   query already establishes descending time, assertion transaction, ordinal,
   and entity-id ordering.
2. Oldest first. This is useful for a conversational transcript but makes a
   long, intentionally unread inbox hide its newest escalation behind earlier
   facts.
3. Unordered cardinality-many traversal. Tied times would flicker and make
   equality suppression unstable; reject.

### Root recipient selection

1. **Root chooses a subset of the cluster's declared escalation humans from
   the current facts — recommended.** A convenience function may explicitly
   choose all, but the default contract preserves the operations ruling that
   root classifies before a human is addressed.
2. Always fan every escalation to every configured human. It is mechanically
   simple but turns the recipient set into an unconditional broadcast policy
   and can create avoidable alert noise.
3. Let each maintenance handler name humans directly. This bypasses root and
   creates one delivery policy per handler; reject.

### Human-recipient wake handling

1. **Accept the redundant armer wake in v1 — recommended.** Correctness and
   delivery already use durable facts plus the render wake; escalation volume
   should be low.
2. Strengthen the one router to distinguish armed-agent recipients without a
   query in Datahike's listener. Consider only after a measured cost or a clean
   precomputed routing-set extension; this is an optimization of the existing
   owner.
3. Add a human-specific recipient attribute, listener, proc, or channel. This
   forks durable messaging and rendering; reject.

### Later push delivery

1. **Accrete refs to declared provider rows only when a provider contract is
   designed; send through `seon.effect` — recommended.** The ordinary message
   remains truth and effect receipts record external attempts.
2. Treat `:seon.human/email` as an implicit SMTP configuration. Identity is not
   provider selection or credentials; reject.
3. Call a push provider from the inbox renderer or root turn. Rendering must be
   pure and agent code must use the guarded effect owner; reject.

## Expected implementation seams

This is not an implementation plan, but the design is incomplete unless a
future implementation accounts for these one-owner changes:

- add a `seon.human` schema resource and ordinary render owner;
- accrete the optional escalation-human ref to the cluster schema;
- accrete the human-address message value and flat recipient errors without
  changing `:my.message/to`;
- strengthen the existing `seon.cluster.message/delivery` resolver to produce
  the same message rows for humans;
- replace the web boundary's recipient-as-user metadata with an optional
  already-resolved human lookup ref, or omit user provenance when none exists;
- make the message render owner resolve human transaction authorship and human
  recipients rather than assuming every ref is an agent;
- leave `seon.error/commit-tx`, the route table, page feed, render wake, and
  agent graph topology unchanged; and
- expose root's cluster-recipient query and sorted value construction through
  ordinary functions, with root still making the escalation decision.

After those changes, the always-current architecture would need to be updated
in the same implementation wave. In particular,
[docs/seon/architecture/data-model.md](../../../seon/architecture/data-model.md)
currently describes transaction users and message endpoints as agents, and
[docs/seon/architecture/ui.md](../../../seon/architecture/ui.md) does not yet
describe watched-human inbox blocks. They are intentionally not edited in this
research-only unit.

## Falsifiable acceptance evidence for a later implementation

- Two cluster branches can each transact the same human email; their entity ids,
  watched-agent refs, and escalation-human refs remain independent.
- A transaction with a pre-existing human lookup ref records
  `:seon.db/user` on the transaction entity. An unknown lookup ref refuses; it
  never creates a human implicitly or substitutes the destination agent.
- An unauthenticated inbound POST records process provenance and no user
  provenance.
- One root result targeting three configured humans commits three message rows,
  each with one human `/to`; a missing fourth human becomes a flat error without
  erasing the three deliverable rows.
- Replaying the same settled run with the same sorted recipient values upserts
  the same message identities rather than duplicating them.
- A watched page derives one labelled inbox block per watching human, while the
  AI walk contains no inbox prose. An unwatched page contains none of those
  human blocks.
- Committing a human-addressed message changes the affected inbox fragment
  through the existing render wake and starts no agent run.
- A handler failure still produces the existing error fact and root-addressed
  message. Only root's subsequent classified result produces human-addressed
  messages.
- No route, notification queue, read/seen flag, polling loop, proc, graph,
  channel, or buffer is added.

## Ugly or misleading output observed

Three current outputs would become actively misleading once humans exist:

1. `seon.cluster.message/render-ai` resolves `/from` and `/to` only through
   `:seon.cluster.agent/id`. It renders every message without an agent sender as
   “From outside this cluster” and cannot name a human recipient
   (`src/seon/cluster/message.clj:429-470`). A human-authored message came from
   a human in this cluster, so that phrase is ugly and false once transaction
   provenance can identify the person.
2. The web submission metadata records the destination agent as
   `:seon.db/user` (`src/seon/render/web.clj:1123-1156`). Debug and provenance
   views therefore present the recipient as the actor. This is a data defect,
   not merely awkward wording.
3. The inbound-size refusal reports raw character counts and a character limit
   (`src/seon/cluster/message.clj:290-297`). Human-visible sizes are supposed to
   use estimated tokens. The human-account work should not copy this output;
   the existing message refusal needs its own owner correction.

No live page output was available to inspect in this research session. The
render findings above are direct source observations, not screenshots or a
claim that a running cluster was exercised.
