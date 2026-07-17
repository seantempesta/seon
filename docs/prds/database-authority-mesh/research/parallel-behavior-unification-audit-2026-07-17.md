---
title: Parallel behavior unification audit
type: research
status: completed
tags: [research, architecture, database, agent, web]
---

# Parallel behavior unification audit

## Result

This read-only audit at Seon `490f949b` found no active first-party namespace
or function generation named `v1`, `v2`, `v3`, `new`, or `legacy` that should
be preserved as a second implementation. The meaningful duplication is
behavioral: alternate routes, request shapes, publication outcomes, tool
names, and old process-record handling remain reachable without a generation
suffix.

The ordered unification targets are:

1. delete the flat `/call` compatibility door and retain only the
   database-seeded `/agent/{id}/call` route;
2. make accepted eval outcome and program publication one atomic behavior;
3. retain one namespaced LLM request map and delete bare-string adapter input;
4. retain `eval_cljs` and delete the MCP `eval` alias; and
5. delete the old no-containment process-record path after its finite local
   evidence has been retired.

Persisted-data readers and external protocol versions are not parallel
implementations. They require explicit migration decisions, not mechanical
renaming. This audit changed no source, tests, or roadmap.

## Method and classification rule

The scan covered active first-party `src/`, `script/`, `test/`, `.codex/`, and
the current issue authority. It searched names and comments containing version
numbers, `new`, `legacy`, `compat`, `remote`, `adapter`, `fallback`, aliases,
and deprecation markers, then traced each candidate through its production
callers and tests.

A finding is a unification target only when normal or recovery execution can
select two public addresses, request shapes, persistence outcomes, lifecycle
behaviors, or maintained names for the same concept. These are not findings by
name alone:

- a current wire or persisted format tag;
- an external provider API version;
- a provider implementation behind one selected protocol;
- a variable describing old and new values inside one transition;
- a wider correctness gate used when selective test evidence is unavailable;
  or
- a reader for historical durable facts that the product still promises to
  open.

## Prioritized unification targets

### 1. One agent action door

`src/seon/route.cljs:74-80,116-118` declares and seeds
`/agent/{id}/call` as the one action door. In parallel,
`src/seon/web/router.cljs:264-272,324-328` publishes a static `/call` route and
labels it back-compat. Both reach `seon.web.reactive.call/handle!`.

The duplicate is not harmless merely because the handler is shared.
`src/seon/web/reactive/transform.cljs:121-147` emits the agent-scoped route for
`my.agent.*` functions, but emits `/call` for another namespace while stating
that the capability gate refuses it. The system therefore maintains a public
address whose only generated use is known to fail and whose absence of an
agent path segment contradicts route ownership.

Retain the database-seeded agent route and one capability handler. A
non-agent function must become an established render error or omitted action,
not a compatibility request. [[flat-call-duplicates-agent-action-door]] owns
the root cause and acceptance proof.

**Shortest falsifier:** render representative agent and non-agent function
references. Prove supported actions contain only `@post('/agent/<id>/call` and
unsupported references produce no flat call. Delete the route, then prove the
agent-scoped route still passes same-origin and capability admission.

### 2. One accepted eval publication outcome

`src/seon/eval.cljs:3193-3205` documents two persistence outcomes for one
executed form. The primary path combines the turn/eval row with accepted
program rows in one transaction at lines 3263-3300. On an eligible failure,
lines 3343-3389 retry without the program rows, stamp a later
`:seon.eval/record-error`, and return a committed eval whose program facts are
explicitly described as unable to survive restart.

The stale-database branch at lines 3323-3328 already demonstrates the retained
concept: do not rerun agent code, reacquire the small database-dependent input,
and retry compilation/publication from frozen data. Extend that one behavior
to program-publication failure instead of treating transcript persistence as
success.

Retain one receipt, turn connection, outcome, and accepted-program transaction.
[[eval-transcript-fallback-drops-program-publication]] owns the root cause and
acceptance proof.

**Shortest falsifier:** force one program-row admission failure after the form
executes. Assert the side effect occurs once, no eval/program success facts or
result handle publish, and a retry constructed from frozen data either commits
all facts in one transaction or remains an error.

### 3. One LLM request shape

`src/seon/ai.cljs:163-190` accepts either a bare context string or a map. Every
provider adapter still calls those helpers: OpenAI-compatible at
`src/seon/ai/openai_compat.cljs:558-568`, Anthropic at
`src/seon/ai/anthropic.cljs:401-410`, DiffusionGemma at
`src/seon/ai/diffusiongemma.cljs:618-627`, and typeahead at
`src/seon/ai/typeahead.cljs:1002-1004`.

Production already has one stronger shape. `src/seon/agent/turn.cljs:683-700`
builds a namespaced request map containing context, abort signal, immutable
config resolution, and optional system prompt/stream flag. Conversely,
`src/seon/ai/dispatch.cljs:81-97` cannot dispatch a bare string because it
requires `:seon.ai/config-resolution`. Tests expose the split assumption:
`test/seon/ai/dispatch_test.cljs:81-92` expects bare input to return a missing
resolution error, while `test/seon/client/provider_routing_test.cljs:129-141`
still invokes dispatch with a string and expects routing.

Retain one closed namespaced request map at dispatch and provider boundaries.
Invalid input should return the existing error-as-data shape, not be coerced
into a second partial request contract.

**Shortest falsifier:** remove bare-string cases from the shared helpers and
run focused dispatch, turn, and provider adapter tests. Every production call
must carry one resolution; malformed direct calls must fail as data.

**Suggested owner:** the model-transport unit of
[[agentic-tool-refinement/roadmap]]. Create an issue only if this cannot be
closed in that owning unit.

### 4. One CLJS MCP evaluation tool name

`script/seon/dev/mcp.clj:1181-1218` advertises both `eval_cljs` and `eval`, with
the latter explicitly labeled a compatibility alias. The dispatcher maps both
names to `execute-eval` at lines 1265-1274, and
`test/seon/dev/mcp_test.clj:21-30` requires the alias to remain public. The root
runbook names only `eval_cljs` for pod evaluation.

Retain `eval_cljs`. Search the actual Codex/Claude client configuration and
prompt corpus before deleting `eval`; do not infer external use from its
presence in the registry test.

**Shortest falsifier:** prove no maintained client configuration or prompt
selects `eval`, remove it from the registry/dispatcher/test, and verify MCP
initialization plus one `eval_cljs` call.

**Suggested owner:** the development MCP portion of
[[agentic-tool-refinement/roadmap]].

### 5. One managed process-record generation

`script/seon/dev/process.clj:1213-1285` recognizes records without a
containment owner and hard-retires their process group instead of using the
current drain protocol. Lines 1596-1598 assign a legacy-retirement reason, and
lines 2064-2072 report `legacy-live`. This is a finite upgrade path for old
local process evidence, not a second supervisor design, but it remains an
executable alternate lifecycle.

Retain it only until every maintained process record either has containment or
has been deliberately retired. Then delete the branch, status/reason data, and
focused tests in the same lifecycle cut.

**Shortest falsifier:** inspect every maintained project-local process record
for `:seon.dev.process/containment`, retire any exact old owner through the
normal operator, and prove cold absence plus `up`/`down` produces only current
records.

**Suggested owner:** the process lifecycle portion of
[[runtime-reliability/roadmap]].

## Persisted compatibility requiring a migration decision

These branches are active code but are not parallel runtime systems.

### Historical eval display values

`src/seon/eval.cljs:3056-3085` reparses old opaque-tagged
`:seon.eval/result-edn` strings and projects them to safe display data. Its
continued need is a data question.

**Falsifier:** query every maintained database for result strings containing
`#datahike/`, `#js `, or `#object`. Delete the reader only after zero matches or
an explicit rewrite/abandonment decision.

### Historical IDs and restore completions

`src/seon/db/id/schema.cljc:10-25` admits exact old 14-character IDs while
current generators publish narrower word or compact values.
`src/seon/db/restore/schema.cljc:65-76` admits completions written before the
plan-digest allocation design. These values participate in reopening and undo,
so rejecting them without inspecting maintained databases would silently make
durable facts unreadable.

**Falsifier:** query maintained databases and retained restore evidence for
matching values. Either migrate them with explicit proof or retain the reader;
never create `id-v2`, `restore-v2`, or a second public API.

## Configuration aliases to retire after downstream inventory

`src/seon/ai/diffusiongemma.cljs:218-235` accepts the old
`SEON_DG_API_KEY_ENV` beside database-resolved credential configuration.
`src/seon/ai/openai_compat.cljs:140-155,461-465` accepts a full
`/v1/chat/completions` endpoint and normalizes it to the preferred root.

Both reuse the same provider execution path. They are vocabulary debt rather
than duplicate engines. Search downstream manifests and deployment
configuration, migrate any use, then remove the aliases from the one adapter.

## Names and fallbacks that are not duplicate behavior

- `seon.ai.openai-compat` is the one adapter for an OpenAI-compatible wire
  family. Provider `agent-adapter` functions are selected implementations of
  one LLM protocol, not generations of the same Seon function.
- `script/seon/dev/changed_test.clj:614-620,713-717` runs the canonical full
  `bin/test-cljs` gate when the selective Shadow manifest is unavailable. It
  widens proof through the same runner rather than maintaining another test
  system.
- `script/seon/dev/artifact.clj:16,25-28` accepts only current manifest version
  5. `test/seon/dev/artifact_test.clj:486-526` explicitly proves versions 1
  through 4 are rejected and must be rebuilt. Earlier claims that the reader
  upgrades old formats are stale.
- `seon.analyzer-info/require-edges-from-source` has one production caller in
  `src/seon/client.cljs:1170-1205`: boot indexing parses full source once where
  no analyzer state exists. `seon.render.sci` reads persisted require-edge
  facts and has no render-time source-reparse compatibility path.
- `new-view-id`, `new-run-row`, `new-intent`, and similarly named functions
  describe values created by one implementation. There is no adjacent old
  function selected at runtime.
- Database and execution protocol versions, restore canonical `v1`,
  autocomplete export `v1`, Datastar `v1`, RunPod `/v2`, and provider `/v1`
  paths are wire, storage, or external API versions. They should change only
  with their protocol or data migration.

## Misleading deprecation prose

`src/seon/agent/ctx.cljs:234-292` marks `file-block`, `file-block-ai`, and
`file-block-html` deprecated while wiring the latter two as current render
slots. `src/my/skills.cljs:234,330-362` similarly wires `skill-block` while its
docstring says deprecated. No `context-rebuild` function exists in active
source. This conflict is already owned by
[[deprecated-skill-render-functions-indexed]].

This is not evidence of a second implementation. The owner must either remove
the false lifecycle label from a canonical function or migrate every caller
and delete the function. It must not add a deprecation blacklist or a renamed
replacement.

## Graduation checks

From one frozen source digest:

1. the router and generated UI expose only `/agent/{id}/call`;
2. every accepted eval outcome and program change shares one transaction;
3. every LLM attempt uses one namespaced request map;
4. MCP exposes `eval_clj` and `eval_cljs`, not `eval`;
5. every managed process record uses current containment;
6. every retained legacy reader has a current matching durable fact or an
   explicit migration obligation; and
7. repository search finds no migration-generation function or namespace
   created to house any fix.
