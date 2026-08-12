---
type: research
status: active
tags: [research, sci, runtime, repl, database]
---

# Result identity and session-surface archaeology

## Scope, required reading, and method

I read the root `AGENTS.md` in full before quarrying. I also read the complete
`Ruling 2026-08-01 #24` and `Ruling 2026-08-01 #26` blocks in
`docs/prds/sci-execution-runtime/plan/README.md`; because the instruction names
the document, I read that file end to end as well. I read the archived
agent-bootstrap entry points and the extracted-rulings document end to end:

- `docs/prds/archive/agent-bootstrap/README.md`;
- `docs/prds/archive/agent-bootstrap/roadmap.md`;
- `docs/prds/archive/agent-bootstrap/AGENTS.md`; and
- `docs/prds/sci-execution-runtime/research/agent-bootstrap-extracted-rulings-2026-08-06.md`.

I also read the active runtime runbook, the full REPL-session and print-path
briefs linked by rulings #24/#26, and the full messaging-state design notes
that contain decision 11. The quarry used only `git log`, `git grep`, and
`git show <commit>:<path>`; it never restored or checked out `src-old/`.

The principal historical points are:

- `333b21b574cc024fccf5235d6725349eeccdfd36`, the last working CLJS evaluator
  before the self-host engine deletion;
- `9e44815f577b4cfda876e49183b7f6ac49bcacf2`, the last old-tree snapshot before
  the 2026-08-05 quarry deletion;
- `b50ebb5a8`, the compact-ID allocator landing; and
- `0d30c829c^`, the earlier comment-shaped result face.

Current-tree citations are against `4b9dbc1e7970`.
The maintained dependency boundary is Datahike's serial transaction allocator:
`reference-code/datahike/src/datahike/db/transaction.cljc:56-88,963-970,1294-1303`
allocates the next entity ID from the database's maximum and resolves
transaction-local tempids; `reference-code/datahike/src/datahike/db.cljc:130`
returns the tempid mapping in the transaction report.

## Findings in one paragraph

The old working result handle was a plain, later-evaluable symbol such as
`result/ck8m2ps4q1ab`. Its ID was an allocated 12-character CUID2 candidate,
not a digest and not `(run, ordinal)`; the serial database writer checked the
candidate against every managed identity before admitting it. A successful
eval additionally bound that symbol into a process-local CLJS runtime and
analyzer, retained at most 200 admitted values, and rendered the handle only
while that live slot existed. That last coupling was the central defect:
historical context bytes changed after eviction or restart. The best carry is
therefore the short, var-like `result/<id>` face and graceful errors, not the
allocator, analyzer mutation, or live cache. In the fresh design the receipt
entity's Datahike EID should be the agent-visible suffix (`result/15664`), the
transcript should append it as the one sanctioned trailing comment, and
resolution should query that receipt at the evaluator's database basis and
read its inline result or content-addressed blob.

## 1. How old result IDs were generated

### The working mechanism

At `333b21b574cc024fccf5235d6725349eeccdfd36:src/seon/eval.cljs:997-1035`,
every successful evaluation was automatically bound under `result/<eval-id>`.
The eval ID had already been minted as the durable `:seon.eval/id`; it was not
computed from the value or from a run ordinal at this seam.

The then-current allocator was introduced in `b50ebb5a8`:

- `b50ebb5a8:src/seon/db/id.cljc:1-8,1013-1040` selects three human-readable
  words for agent IDs and 12-character CUID2 strings for other generated
  persistent identities.
- `b50ebb5a8:src/seon/db/id.cljc:31-61,82-94` requires the compact form
  to start with a lowercase letter and contain 12 lowercase alphanumerics;
  it retains the earlier 14-character grammar for existing rows and permits
  16 candidate attempts.
- The landing's source comment at `b50ebb5a8:src/seon/db/id.cljc:48-50` gives
  the result-specific reason: a leading letter keeps `result/<eval-id>` a
  readable symbol and the alphabet avoids CLJS-munging hazards.
- `333b21b574cc024fccf5235d6725349eeccdfd36:src/seon/db/id.cljc:670-711,795-853`
  makes the database writer the authority: it rejects duplicates within the
  incoming transaction and collisions with any existing managed identity,
  then retries a fresh candidate. Thus the random generator was only
  collision-improbable, but a colliding managed identity could not commit.
- `333b21b574cc024fccf5235d6725349eeccdfd36:src/seon/host/context.clj:2024-2099`
  shows an eval start obtaining a policy-selected candidate and committing the
  running receipt before execution, with bounded collision retry.

This was allocated randomness plus serialized collision detection. It was not
monotonic, content-derived, or derived from `(run, ordinal)`.

### Earlier and later identities

The compact allocator replaced two older families:

- `b50ebb5a8^:src/seon/db.cljs:307-330` minted a 14-character
  `<three letters>-<YYMMDDHHmm>` identity, for example `Kpx-2605232138`. Its
  recorded intent was an LLM-readable face with a minute-sortable suffix.
- `b50ebb5a8^:src/seon/runtime.clj:289-332,953-957` also had a six-character
  base-62 `SecureRandom` generator backed by a process-local collision set;
  reload cleared the set and the comment called collision risk negligible.

The legacy 14-character grammar was broader in deployed data than the
letters-only snapshot suggests. The parser regression contains five real eval
rows with digit-leading IDs such as `0xO-2606281659`, which made
`result/0xO-2606281659` unreadable as a Clojure symbol
(`9e44815f577b4cfda876e49183b7f6ac49bcacf2:test-old/seon/repl/parse_test.cljc:1003-1029`).
That is evidence of the stored population, not a claim that the exact
letters-only `new-id!` source shown above emitted those rows.

After the self-host evaluator was deleted, the old JVM receipt used
`(pr-str [run-id ordinal claim-epoch])`
(`9e44815f577b4cfda876e49183b7f6ac49bcacf2:src-old/seon/eval/receipt.cljc:84-92`).
That identity was derived, but it did **not** restore result symbols: the
archived issue records that the JVM evaluator discarded the retained value and
never bound `result/<id>`
(`9e44815f577b4cfda876e49183b7f6ac49bcacf2:docs/seon/issues/archive/jvm-result-symbols-not-bound-r32.md:8-27`).
It is therefore not the identity scheme of the working result-reference system.

## 2. How results were displayed

The old system had two faces.

The earlier face was a form followed by comment-shaped output:

```clojure
(+ 1 2)
;=> 3 ; result/ck8m2ps4q1ab
```

`0d30c829c^:src/seon/agent/ctx.cljs:708-863` constructs exactly the `;=>`
prefix and ` ; result/<id>` suffix. A clipped value added `(N of M tokens)`;
rows from a prior process received no live handle.

The final old face moved the ordinary short result inline behind reserved
glyphs:

```clojure
(+ 1 2) ⟹ 3 ⟸ result/ck8m2ps4q1ab
```

Large or multiline values put `⟹ <value>` on the following line while the
handle remained on the form's first line. The exact construction and clipping
are at
`9e44815f577b4cfda876e49183b7f6ac49bcacf2:src-old/seon/agent/ctx.cljc:433-456,513-539,667-703`.
Source, result, and stdout each had bounded rendering; older result bodies
received a decaying cap. Condensing was display-only: the handle still named
the admitted live value. However, admission itself could replace an oversized,
lazy, or opaque raw value with an honest descriptor, so expanding a handle did
not promise recovery of a raw value that admission had refused.

The final renderer emitted a handle only if the process-local result slot was
still present. That made the rendered past depend on mutable cache membership,
the defect discussed under failure modes below.

For the fresh direction, both old output fences are dead. Decision 11 requires
a real REPL transcript—form, then actual computed result—with no `;=>` pseudo
result or decorative glyph protocol. The owner's current result ruling permits
one sanctioned trailing comment, so the composed face should be:

```clojure
(+ 1 2)
3 ; result/15664
```

`seon.print` owns the honest value/elision before the comment;
`seon.render.transcript` owns appending the receipt-derived handle. An elided
face keeps the same suffix because elision is precisely when requery matters.

## 3. How agents referenced and resolved results

The reference was a plain var-like symbol, not reader syntax and not a function
call:

```clojure
result/ck8m2ps4q1ab
```

`333b21b574cc024fccf5235d6725349eeccdfd36:src/seon/eval.cljs:997-1035`
says this one surface superseded both `(result ...)` and `*1/*2/*3`. Binding
installed the admitted value twice: in `globalThis.result.<munged-id>` for the
runtime and as an analyzer definition in the reserved `result` namespace.
`333b21b574cc024fccf5235d6725349eeccdfd36:src/seon/eval.cljs:1433-1552`
shows dual install/removal, newest-order refresh, pruning, and rollback after a
partial bind failure.

The reply reader recognized only a bare, standalone symbol whose namespace was
exactly `result`
(`9e44815f577b4cfda876e49183b7f6ac49bcacf2:src-old/seon/repl/parse.cljc:438-481`).
Tests prove it had to occupy its own logical line and that a forged runtime
prefix on the same line could not promote prose to a reference
(`9e44815f577b4cfda876e49183b7f6ac49bcacf2:test-old/seon/repl/parse_test.cljc:490-542`).

Resolution first used the live runtime binding. A special single-symbol path
then distinguished a missing result reference from an ordinary unresolved Var:
`333b21b574cc024fccf5235d6725349eeccdfd36:src/seon/eval.cljs:1080-1120,1370-1424`
queries `[:seon.eval/id id]` at the current database basis and returns a flat,
helpful value distinguishing nonexistent, errored, evicted, and prior-process
receipts. It did not throw into the agent loop. Restart or eviction meant the
raw live value was gone; the durable row could diagnose the miss but did not
reconstruct it.

The fresh resolver should preserve the same symbol grammar but reverse the
authority: parse the suffix as a receipt EID, query that receipt against the
database value ambient to the calling eval, and decode either its faithful
inline value or its referenced blob. If the basis predates the EID, the entity
is absent; if the entity exists but its value is unavailable or its blob has
been reclaimed, return a flat result-unavailable value naming the receipt,
basis transaction, and reason. No analyzer Var or process cache decides truth.

## 4. Storage, deduplication, and lifetime

The working old mechanism kept the reusable admitted value only in process:

- `333b21b574cc024fccf5235d6725349eeccdfd36:src/seon/render/value.cljc:330-335`
  sets a 200-value retention cap, with bounded node/weight admission.
- `333b21b574cc024fccf5235d6725349eeccdfd36:src/seon/eval.cljs:1026-1046,1058-1069,1356-1367`
  evicts in runtime object insertion order and has no second retention mirror.
- `333b21b574cc024fccf5235d6725349eeccdfd36:src/seon/eval.cljs:1585-1596`
  ensures the transcript and live handle see the same admitted value.

There was no deduplication among live result slots. The durable eval row held a
bounded `result-edn` projection, not an automatically split reusable value.
The separate old blob capability was content-addressed SHA-256
(`9e44815f577b4cfda876e49183b7f6ac49bcacf2:src-old/my/blob/core.cljc:7-19`),
but automatic result spill was not part of the working handle mechanism.
Lifetime was therefore: until the oldest of more than 200 values was evicted,
or until process restart. The receipt outlived that slot but only diagnosed its
loss.

Fresh receipt storage already has the correct split. Current
`src/seon/cluster/loop.clj:464-552` stores the complete `result-edn` inline
unless a bounded window plus blob is measurably smaller; the blob case records
the inline window, SHA-256 digest, and original size. Current
`src/seon/blob.clj:138-172,243-269` publishes under a reachability permit,
skips an already-present digest, verifies digest/size, and commits the database
root. Thus content dedupe is by digest and result identity remains the receipt,
not the blob. Retention belongs to receipt/branch and blob-root GC policy—not a
hidden 200-slot process cache.

## 5. The old readline/prompt line

The archived bootstrap PRD proposed a mutable
`:seon.cluster.agent/readline` attribute. The visible bootstrap form would set
it to a compact status line containing current work, one tip, pending-message
count, and prior-turn duration
(`docs/prds/archive/agent-bootstrap/README.md:43-71`). The extracted rulings
explicitly say this bootstrap/readline mechanism was deleted and is not
forward authority
(`docs/prds/sci-execution-runtime/research/agent-bootstrap-extracted-rulings-2026-08-06.md:22-24`).
`git grep` at both `401fd300e` and the final old snapshot finds no
`:seon.cluster.agent/readline` implementation in source, schema, or tests. The
proposal never became the actual prompt owner.

The actual old bootstrap prompt was renderer-derived on every render:
`9e44815f577b4cfda876e49183b7f6ac49bcacf2:src-old/seon/agent/ctx/transcript.cljc:634-701`.
Its tail was structurally:

```text
; <ns> · turn <n> · loop <used>/<cap> · <state> · <now> · agent <id>
<ns>=>
```

Root additionally received host telemetry. It did not carry pending-message
counts, waiting callers, or last-turn duration. Unanswered-message state was
rendered in the transcript event log instead
(`9e44815f577b4cfda876e49183b7f6ac49bcacf2:src-old/seon/agent/ctx/transcript.cljc:1288-1332`),
and `readline-block` was root-only at lines 703-707. The line also read clock
and process telemetry below the cache boundary, so it was ambient renderer
state rather than a session-set bootstrap value.

Transfer lesson: do not revive a mutable PS1 fact or bootstrap setter. Pending
messages and waiting callers, when useful, are database queries rendered by
their owning status block. The REPL transcript remains a session; ambient
status composes around it without becoming result identity or changing cached
history.

## 6. Failure modes and transferable lessons

| Failure | Evidence and fix | Transferable lesson |
|---|---|---|
| Ordinary forms returned `nil` | `9e44815f:docs/seon/issues/archive/ordinary-eval-statement-context-dropped-result.md:10-34` records 25 wrong `nil` results for `(+ 20 22)`; `35cd07ac` evaluated every form as `:expr`, restoring `42`. | A handle must attach to the evaluator's real expression value, never a renderer reconstruction. |
| Bare handles were parsed as prose | `9e44815f:test-old/seon/repl/parse_test.cljc:490-542`; fixed in `1fe6a5e23`. | Reserve and parse the exact standalone `result/<id>` grammar structurally. |
| Legacy digit-leading IDs were unreadable | `9e44815f:test-old/seon/repl/parse_test.cljc:1003-1029` found five real rows such as `result/0xO-…`; 12 of 128 sampled references had reader errors overall. | Agent-visible IDs must be valid symbols by construction and avoid ambiguous alphabets. Numeric EIDs are safe after the fixed `result/` namespace slash. |
| Retained values exhausted memory | `9e44815f:docs/seon/issues/archive/eval-memory-safety.md:10-33,39-52` records a 9.7-million-character pull and a later database scan OOM; bounded query/pull, admission caps, and descriptors fixed the class. | Query one receipt by identity; never scan history to rebuild a live-result registry, and never retain unbounded raw values. |
| Historical context changed after eviction/restart | `9e44815f:docs/seon/issues/archive/ai-context-is-not-pure-over-database-value.md:43-62,84-99` records cache membership adding/removing handles from old transcript bytes; the fix removed cache-dependent handles from the cacheable body. | The receipt fact determines whether a handle renders. Runtime cache membership never does. |
| JVM migration lost result reuse | `9e44815f:docs/seon/issues/archive/jvm-result-symbols-not-bound-r32.md:8-27` records the fresh evaluator discarding the retained value and binding no symbol. | Result reuse is an explicit receipt-query contract, not a side effect one evaluator happens to perform. |
| Prompt advertised the wrong loop cap | `9e44815f:docs/seon/issues/archive/configured-turn-limit-masks-mode-specific-budget.md:10-40` records batch displaying 20 while stream fallback allowed 60; `5cfc0127` derived the shown cap from actual policy. | Ambient prompt/status values must be derived from the same facts that enforce them. |
| Model copied runtime scaffolding | `9e44815f:docs/seon/issues/archive/narration-ghost-echo-not-neutralized.md:10-29,73-81` records ghost runtime text; the durable fix preserved raw replies and rendered authored narration structurally. | Do not regex-rewrite model output. Keep runtime decoration minimal, sanctioned, and outside authored source. |

No genuinely new issue is needed from this archaeology: the historical failure
classes are already recorded, and the current UUID inconsistency is the design
question this report was commissioned to settle.

## 7. Mechanism verdict table

| Mined mechanism | Verdict and fresh seam | Why |
|---|---|---|
| Bare `result/<id>` symbol | **Adopt**, resolved from receipts | It is concise, readable, pasteable Clojure and already displaced function-call and `*1` history surfaces. |
| CUID2/random candidate for result ID | **Dead** | It is allocated state plus collision machinery; a receipt EID is shorter and collision-impossible in its database scope. |
| Dual runtime/analyzer binding | **Dead** | It makes process state an identity authority and disappears on restart. The symbol resolver should query the receipt. |
| 200-slot process retention | **Dead** | It made history impure and reuse restart-sensitive. Receipt/blob retention owns lifetime. |
| Bounded admission and honest descriptor | **Adopt** at admission and `seon.print` | It prevents opaque/lazy/oversized values from pretending to be faithfully reusable. |
| Comment-shaped `;=>` output | **Dead** | It is pseudo-REPL output and was copied as scaffolding. |
| `⟹ ... ⟸ result/...` glyph fence | **Dead** | It is a second display protocol and conflicts with strict REPL fidelity. |
| Stable suffix despite value elision | **Adapt** in `seon.render.transcript` | Append the one sanctioned `; result/<receipt-eid>` comment after the actual printed/elided value. |
| Durable receipt diagnosis on a miss | **Adopt** in receipt resolution | Preserve precise absent-at-basis, failed, unavailable-value, and missing-blob errors as flat values. |
| Old manual SHA-256 blob store | **Adopt** through current receipts/blob | Current content-addressed storage already supplies dedupe and integrity; hide its long digest behind the receipt handle. |
| Derived `(run, ordinal)` receipt key | **Adapt** as an internal idempotency fact | It makes re-execution unrepresentable, but its long serialized tuple is not the agent face. |
| Bootstrap-set mutable readline | **Dead** | It was proposed, not implemented, and conflicts with database-derived status blocks. |
| Renderer-derived ambient prompt tail | **Adapt only as queried status renders** | Actual state may be useful, but it must not enter cached history or carry a second remembered status authority. |

The ownership split is exact: receipts own identity, result bytes/digest, and
retention; `seon.print` owns the faithful result or elision value;
`seon.render.transcript` owns the one trailing handle comment.

## 8. System-wide ID generation archaeology

### Token-count convention

Current `src/seon/ai/tokens.cljc:19-40` deliberately has no tokenizer and
estimates `(quot character-count 4)`. The estimates below therefore measure
the repository convention, not actual model tokenization. For opaque UUIDs the
owner's observed design budget is approximately 20+ tokens even though the
house heuristic reports 9 for 36 characters. “Emit” counts the smallest face
an agent must write to reference the identity, not the larger surrounding
lookup form.

| Site | Generator and evidence | Face / typical length | Claimed scope and collision property | Estimated agent emission |
|---|---|---|---|---|
| Old result/eval | 12-char CUID2 candidate plus serial writer collision check; `b50ebb5a8:src/seon/db/id.cljc:1-8,48-50`; `333b21b5:src/seon/db/id.cljc:670-711,795-853` | `result/ck8m2ps4q1ab`, 19 chars | Unique across managed old identity attributes after commit; random candidate alone only probable | 4 estimated tokens |
| Older result/eval | Three random letters + minute timestamp; `b50ebb5a8^:src/seon/db.cljs:307-330` | `result/Kpx-2605232138`, 21 chars | Intended persistent ID; deployed grammar admitted reader-breaking digit leaders | 5 estimated tokens |
| Old agent | Three lowercase human-readable segments from `human-id`/`RandomHumanReadableIdGenerator`; `b50ebb5a8:src/seon/db/id.cljc:1-25,41-56`; allocation at `333b21b5:src/seon/agent.cljs:650-685` | For example `gentle-lime-47`, about 14 chars | Unique across every allocator-managed identity after the serial writer accepts it | About 3 estimated |
| Old message | Same compact allocator; `333b21b5:src/seon/agent/message.cljc:331-385,505-538` | 12-char CUID2 | Same cross-attribute committed uniqueness; message write allocated any paired plan IDs in the same round | 3 estimated |
| Old run and turn | Same compact allocator; `333b21b5:src/seon/agent/run.cljs:490-535`; `333b21b5:src/seon/agent/turn.cljs:580-625` | 12-char CUID2 | Same cross-attribute committed uniqueness | 3 estimated |
| Old schedule declaration/fire | Schedule declaration used the same compact allocator; a due occurrence opened an allocated run and used the agent/minute run log as its double-fire fence; `333b21b5:src/seon/agent/schedule.cljs:24-45,219-250` | 12-char declaration ID; no separate fire ID | Declaration had allocator uniqueness; occurrence uniqueness was only per agent/minute run-history policy | 3 estimated for declaration |
| Old error | Flat error values had no system-wide durable `:seon.error/id` (no such source occurrence at `333b21b5`) | Message/kind value, no handle | No identity claim | Not referenceable by an error ID |
| Old capability/database operation | UUID supplied as capability op ID or database request ID; `333b21b5:src/seon/agent/message.cljc:517-527`; `333b21b5:src/seon/db/host.clj:50-65,180-203` | UUID, 36 chars | Collision-improbable process/protocol request scope | 9 estimated; approximately 20+ actual by owner constraint |
| Old blob | SHA-256 content hash; `333b21b5:src/my/blob.cljc:28`; `333b21b5:src/my/blob/core.cljc:7-19` | 64 lowercase hex chars | Content-addressed dedupe; collision cryptographically improbable | 16 estimated and normally hidden |
| Current run | `random-uuid` before `open-tx`; `src/seon/cluster/loop.clj:1177-1205` | UUID, 36 chars | Collision-improbable global UUID; needed before current open transaction only because the API requires it | 9 estimated; approximately 20+ actual by owner constraint |
| Bootstrap run | Derived `bootstrap:<agent-id>`; `src/seon/bootstrap.clj:131-136` | Semantic prefix, variable | Unique per bootstrap agent under the run identity attribute | Variable, usually 3+ |
| Current form and eval receipt | `(pr-str [run-id ordinal])`; `src/seon/cluster/run.clj:464-478,537-590` | `["<uuid>" 0]`, about 42 chars | Collision-impossible if run ID is unique; at most one receipt per run ordinal, enforced in transaction | 10 estimated |
| Proposed result face | Receipt Datahike EID | `result/15664`, 12 chars | Collision-impossible within the database/branch allocation scope | 3 estimated (bare EID `15664` is 1) |
| Model attempt | Derived `<run-id>-attempt-<ordinal>`; `src/seon/cluster/loop.clj:835-841` | About 46+ chars with UUID run | Unique within run/attempt ordinal | 11+ estimated |
| Outbound message | Derived `<run>-<form>-message-<index>`; `src/seon/cluster/message.clj:195-201` | About 48+ chars | Unique within one run form's ordered emitted messages | 12+ estimated |
| Inbound message | Derived `inbound-<basis max-tx>-0`; `src/seon/cluster/message.clj:253-265,299-304` | Usually 12–20 chars | Serialized-writer predecessor basis plus ordinal; unique in branch transaction sequence | 3–5 estimated |
| Assignment message | Derived `assignment-` + `[problem-eid recipient]`; `src/seon/cluster/message.clj:244-251` | Usually 25–40 chars | Stable per problem entity and recipient | 6–10 estimated |
| Agent | Caller-supplied semantic ID; `src/seon/cluster/agent.clj:90-105`; root is declared at `src/seon/cluster.clj:1192` | `root` or semantic name | Unique attribute in a cluster database; not generator-owned | Often 1–4 estimated |
| Error fact | Caller UUID in `src/seon/cluster/loop.clj:601-625` and corresponding cluster fault assembly; `src/seon/error.clj:263-340` normalizes caller identity | UUID, 36 chars | Collision-improbable; SHA-256 signature is recurrence/dedupe evidence, not entity identity | 9 estimated; approximately 20+ actual by owner constraint |
| Effect/op receipt | `(pr-str [run-id form-ordinal effect-ordinal])`; `src/seon/effect.clj:1-7,418-497` | About 44 chars with UUID run | Unique within deterministic evaluation order; receipt commits before external handler | 11 estimated; full lookup ref about 16 |
| Schedule task | Declared semantic ID such as `root/maintenance/...`; `src/seon/schedule.clj:35-58` | Variable semantic string | Unique task declaration in cluster | Variable |
| Schedule fire | `(pr-str [task-id nominal-at])`; writer rederives it; `src/seon/schedule.clj:227-245,282-360` | Variable tuple, often long | Collision-impossible per task and nominal instant; retry idempotent | Variable, usually 10+ |
| Maintenance receipt/request/result/error | Prefix derived from claimed fire or receipt; `src/seon/schedule.clj:231-245` | Long nested semantic strings | Collision-impossible given fire identity | Not intended as agent shorthand |
| Context capture/contribution | `<run>-context-<basis-t>` and positional suffixes; `src/seon/context.clj:99-124` | UUID-derived, 48+ chars | Unique per run and database basis, then contribution position | 12+ estimated |
| Problem | `problem-` + `(pr-str [run ordinal])`; `src/seon/cluster/work.clj:153-160` | UUID-derived, about 50 chars | Unique per run form | 12+ estimated |
| Blob | SHA-256 of content; `src/seon/blob.clj:138-183,243-269` | 64 lowercase hex chars | Same content dedupes; different content collision is cryptographically improbable, not impossible | 16 estimated; should normally remain hidden |
| Datahike entity | Serial writer allocates `inc max-eid`; dependency lines cited in the ledger | Decimal such as `15664`, 5 chars | Collision-impossible within a database/branch's entity-ID space | 1 estimated |
| Transaction-local entity | Datahike tempid resolved in transaction report | Arbitrary local token, not persisted | Collision-free within transaction resolution; no durable face claim | Agent should not emit after commit |
| Ephemeral coordination | UUID/temp name, listener key, tab ID, scratch branch, or deterministic name-UUID in filesystem/operator/test owners | Usually UUID or implementation object | Process/file/UI-local, not durable domain identity | Not an agent identity surface |

The last row includes the remaining current `random-uuid` sites used for
staging files, listener keys, web tabs, test runs, scratch proof branches, and
temporary exports. Those are not evidence for a universal durable ID format:
they are local coordination identities and should keep using the dependency or
OS primitive appropriate to their scope.

For completeness, those current non-domain minting sites are
`resources/seon/operator/state.clj:157,184,286` (atomic-write temp name and
deterministic root identities), `src/seon/fs/jvm.clj:524` and
`src/seon/cluster/export.clj:287` (staging names),
`src/seon/cluster/agent.clj:505` and `src/seon/schedule.clj:705` (listener
keys), `src/seon/render/web.clj:987` (tab ID),
`src/seon/cluster/store.clj:84,159` (schema lock temp name and deterministic
store ID), `src/seon/cluster/source.clj:119` (scratch source directory),
`src/seon/cluster/curate.clj:164-166`, `src/seon/eval/drive.clj:22,371`, and
`src/seon/bootstrap_drive.clj:96,353-354,420-421` (proof/evaluation harness
names), `src/seon/test/runner.clj:489-549,649` and
`script/seon/dev/changed_test.clj:284` (test rows and logs), and
`script/seon/fresh_operator.clj:1459` (process-record generation). The
`random-uuid` exposed inside SCI at `src/seon/sci/eval.clj:441-449` is ordinary
agent program behavior, not a Seon domain-ID allocator. Deterministic schema,
source, render, plan, and error signatures elsewhere use `schema/sha-256` as
content/change detection; they are digests, not entity identities.

No recorded true UUID/CUID/SHA collision was found. The material near-misses
were instead semantic: digit-leading “compact” IDs that were not valid symbols;
process-local collision memory that vanished on reload; allocator collision
machinery coupled to transaction-envelope changes; and long derived faces
which are exact but expensive for an agent to transcribe.

## 9. One generator verdict for the fresh design

### Recommendation: one identity policy, one agent-visible generated face

Use the **database entity ID** as the generated identity of every durable row
and as the only short generated ID shown to agents. Do not create another
random application ID merely to name an entity the database has already named.
For results, the exact face is `result/<receipt-eid>`.

This is one story rather than a menu of random generators:

1. A durable thing is committed with a Datahike tempid and thereafter addressed
   by its EID. Serialization makes collision impossible within the database
   branch. The transaction report makes the EID available immediately after
   the commit.
2. A fact that must be idempotently named **before** its row exists derives a
   canonical key from already-existing parent EIDs plus a local ordinal or
   nominal instant. The derived key is a transaction fence, not the ordinary
   agent-visible face.
3. A thing already has a natural declared identity—agent name, namespace,
   schedule task name—so the owner supplies that semantic identity. It is not
   “generated.”
4. Content uses SHA-256 because equality of content is the point. The long
   digest remains behind the owning entity's EID in ordinary agent surfaces.
5. Transaction-local construction uses Datahike tempids and never persists or
   renders them as durable IDs.

This policy is derived-over-remembered everywhere derivation is meaningful,
and uses the database's one native serial allocation only for database
entities. It beats a universal UUID/CUID generator on both relevant axes:
collision is impossible within the claimed scope, and the face is much
shorter. It also avoids claiming that a content digest can make two equal
events distinct, or that a monotonic process counter survives restart.

### Which identities genuinely need pre-commit availability

- **External effects:** the system needs an idempotency/receipt identity before
  dispatch, but current `seon.effect` already commits the receipt before the
  handler (`src/seon/effect.clj:470-505`). Commit it with a tempid, obtain the
  receipt EID, then dispatch under that EID. No UUID is required.
- **Runs:** the run must exist before the paid model call, not before its own
  opening transaction (`src/seon/cluster/loop.clj:1177-1205`). Let `open-tx`
  allocate the EID and use it for subsequent receipt derivations. The current
  pre-open UUID is API shape, not an external necessity.
- **Messages and errors:** neither crosses an external idempotency boundary
  before its recording transaction. Allocate an entity and use the returned
  EID; derive recurrence signatures separately where needed.
- **Scheduled fires:** these genuinely need deterministic replay identity
  before insert because restart derives the same nominal occurrence. Keep the
  canonical `(task-eid, nominal-at)` uniqueness key, but expose the committed
  fire EID afterwards.
- **Forms, eval receipts, attempts, context contributions, and problems:**
  their parent run is already committed. Derive an internal uniqueness tuple
  from `(run-eid, ordinal)` where idempotent construction needs it, and use the
  resulting entity's EID for references.
- **Blobs:** their identity must be known before publication and must dedupe
  equal bytes, so SHA-256 remains the correct exception. Agents normally refer
  to the receipt/blob-root entity, not the digest.

### Result resolution and loss semantics

Evaluating `result/15664` performs a receipt query at the evaluator's ambient
database value. It returns the faithful inline value or loads and verifies the
blob named by that receipt. A database value older than entity 15664 produces
an absent-at-basis flat error. A retained receipt with a reclaimed/corrupt blob
produces an unavailable-blob flat error. Retraction or branch GC may end the
receipt's lifetime according to the declared retention policy, but process
restart and cache eviction do not.

This does give up one old capability honestly: an opaque tier-local object that
was never faithfully admitted into receipt data cannot be resurrected by a
receipt query. The system must either admit an identity-only projection or
return an explicit non-reusable result; it must not secretly make a
process-local object cache the authority behind a durable-looking handle.

The old system got the **surface** right—`result/<short-valid-symbol>`—but not
the identity source or lifetime. The fresh system should keep that exact
ergonomic lesson with `result/<receipt-eid>`, derive internal idempotency keys,
and retire UUID/CUID allocation from agent-visible durable identities.
