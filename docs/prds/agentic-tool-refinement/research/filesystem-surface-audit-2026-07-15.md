---
type: research
status: active
tags: [research, agent, capability]
---

# Filesystem surface audit — 2026-07-15

## Outcome

`seon.agent.fs` is the right namespace owner, but its standing compact card
currently presents twelve functions from at least two generations. The largest
small-model quality gain is to converge on one bounded, SHA-bearing read and
one deterministic mutation family. Today the natural old names
`read-file`/`edit-file` compete with the safer `view`/`replace!`/`insert!`, and
the two families return incompatible failures. Three smaller overlaps compound
the choice problem: `stat` versus `file-exists?`, `grants` versus `home-dir`,
and `list-dir` versus `walk-dir`.

The namespace measured 2,672 estimated tokens in the latest byte-stable
compact projection: twelve callable rows, sixty schemas defined in the
namespace, and ten referenced closure schemas. Function declarations cost 456
tokens while schema records cost 2,032. Merely shortening docstrings will not
fix the surface. Removing superseded functions and collapsing duplicate
request/response shapes changes both the choices and their schema closure.

No source, task, scorer, dependency, or live cluster was changed for this
audit. The task candidates below use the existing native Inspect
`frozen_tool_rows` workspace and outcome scorer; they require no new harness
and contain no API coaching.

## Dependency ledger

- Seon revision `08a9ef33917e7b2df66162d61db09078c4da6021` supplies the
  audited source. `src/seon/agent/fs.cljs` owns the public capability and
  colocated schemas; `src/seon/agent/fs/internal.cljs` owns grant enforcement,
  paging, walking, and the legacy edit helpers; and
  `src/seon/agent/fs/match.cljc` owns the pure deterministic anchored matcher.
- ClojureScript `1.12.145` supplies the Node-hosted implementation and analyzer
  metadata. `src/seon/analyzer_info.cljs` projects the positive
  `^:seon.fn/agent-facing?` declarations that make the twelve rows visible.
- Malli `0.20.0` supplies callable schemas. The compact namespace projection
  derives the function rows and their complete transitive schema closure from
  persisted database facts; absence of positive eligibility is the removal
  mechanism, not a renderer blocklist.
- Inspect AI revision `05322696a0f784ec399ef6abbafd3d2a250ea9cc`
  supplies `inspect_ai.tool._tools._read_file` and `_list_files`. Its built-in
  `read_file` is one line-numbered operation with offset/limit; `list_files` is
  one operation whose optional depth selects immediate versus recursive
  listing. They are reference shapes, not tools to inject into Seon.
- Clojure MCP revision `d801e2fa53b0956502ca37dd46ef6e88d447211e`
  supplies a second first-party comparison. Its one `read_file` owns bounded
  raw and collapsed views; `file_edit` requires one unique old string; and
  `file_write` refuses a stale overwrite after a tracked read. Seon should not
  copy its mutable timestamp registry or its formatting pipeline, but the
  source demonstrates that natural operation names can own the strong path.
- `src-inspect-ai/src/seon_inspect/generators.py`,
  `tasks/frozen_tool_rows.py`, and `tool_scorers.py` are the existing Seon task,
  workspace, and oracle owners. The selected scorer re-reads exact workspace
  outcomes, parses Clojure/EDN, and can evaluate behavioral ClojureScript
  cases. The retained native `.eval` already carries turn/eval evidence needed
  to classify tool selection without adding a trajectory scorer.
- The measured context and complete positive inventory come from
  [[context-schema-closure-measurement-2026-07-15]] and
  [[tool-namespace-colocation-audit-2026-07-15]]. Historical runs under
  `evals/runs/2026-07-03-first-dev-pass/` and
  `evals/runs/2026-07-10-minimal-buildup/observer/` are diagnostic only: the
  former predates native admitted Inspect execution and includes hot-reload
  contamination, while the latter used explicit function coaching.

## Complete eligible inventory

All twelve positive functions live in `seon.agent.fs` and are resident in both
ordinary and root home context under alias `fs`. `configure!` is public but is
correctly absent from the agent-facing surface; the host/database-selected
grant is not an ordinary agent mutation.

| Function | Input contract | Output contract | Current semantic owner |
|---|---|---|---|
| `grants` | no arguments | exact map of `allowed-roots`, `read-only?`, `locked?` | enforced filesystem grant |
| `read-file` | path; optional encoding/from-line/max-lines | fs `ok?` envelope; raw content, optional paging facts and SHA | unnumbered raw read, unbounded by default |
| `write-file` | path/content; optional encoding | fs `ok?` envelope with path only | create or unfenced full overwrite |
| `edit-file` | path plus either inclusive line range/content or old/new string | legacy edit envelope with counts, totals, context | unfenced line replacement and exact-once replacement |
| `list-dir` | path | fs `ok?` envelope with names only | immediate directory listing |
| `stat` | path | fs `ok?` envelope with `dir?`, `file?`, opaque `mtime` | typed path observation |
| `file-exists?` | same stat request | bare boolean | collapses every stat failure to false |
| `home-dir` | no arguments | bare string, but throws when unavailable | process environment discovery |
| `walk-dir` | path; optional extension/glob/hidden/sort/cap | fs `ok?` envelope with absolute paths, bounded count/truncation/hint | recursive filtered listing |
| `view` | same four fields as `read-file` | structurally the same success fields as read response | bounded line-numbered read plus SHA |
| `replace!` | path/find/replace; optional count/all/near/SHA/encoding | anchored discriminated union with SHA, landing range, counts, excerpt or `seon.error` facts | deterministic content-anchored mutation |
| `insert!` | path/content plus exactly one line anchor; optional encoding | same anchored discriminated union | deterministic line-anchored insertion |

The visible request and response records reveal four envelope dialects despite
the namespace docstring's claim that every function is map-in/map-out with
`:seon.agent.fs/ok?` and `:seon.agent.fs/error`:

- ordinary reads/writes/list/stat/walk use `:seon.agent.fs/error` on failure;
- anchored edits use `:seon.error/message` plus optional `:seon.error/data`;
- `file-exists?` returns a boolean and intentionally erases denial, missing,
  permission, and other failures into the same `false`; and
- `home-dir` returns a string or throws.

That inconsistency is visible in the complete schemas, not merely hidden in
implementation. It makes composition harder because a small model cannot carry
one result predicate or one error-recovery branch across the namespace.

## Exact overlap analysis

### Read generation: `read-file` versus `view`

The request maps have the same path, encoding, 1-based from-line, and max-lines
fields. Their success maps have the same path, content, page facts, total, and
SHA fields. The behavioral differences are defaults and presentation:

- `read-file` returns raw text and reads the whole file unless paging is
  explicitly requested;
- `view` defaults to 100 lines, numbers each returned line, and orders the SHA
  before content so transcript clipping cannot hide the edit fence.

This is duplicate interface shape attached to two competing names. Both pinned
reference implementations put the bounded, line-numbered model view under the
natural name `read_file`; Clojure MCP adds raw/collapsed modes under that one
owner rather than advertising a second read operation. The recommended owner
is therefore `read-file`, strengthened in place to own the current safe `view`
defaults and response ordering. A raw programmatic projection may remain an
explicit option on that same request if experiments prove it necessary. Delete
or unmark `view` in the same change; do not add a third wrapper.

### Mutation generation: `edit-file` versus `replace!`/`insert!`

`edit-file` exact mode and `replace!` both substitute old content. The legacy
path requires exactly one byte match, has no SHA fence, no line-window
disambiguation, no all/count contract, and no normalized near-miss candidates.
Its line-range mode overlaps anchored replacement and insertion while allowing
a stale line number to mutate a changed file. Its result uses a different
failure key and reports a context window rather than the shared anchored
landing envelope.

`replace!` and `insert!` already share one response schema and one pure line
formatter. `replace!` preserves untouched bytes, refuses ambiguity, supports
an exact expected count or all occurrences, and can reject a stale SHA.
`insert!` owns append/prepend and an exact one-line anchor. Together they cover
the frozen edit outcomes without the unfenced legacy path. Retain them as the
one visible mutation family and delete or unmark `edit-file`; keep the pure
matcher as their single decision owner.

The strongest existing diagnostic evidence agrees. In the coached July 10
drive, repeated SHA failures caused the model to switch to `edit-file`; one
line-range edit reported success but inserted four lines without removing the
intended old lines, and the model only discovered the damage two evals later.
It then rewrote the whole file. That run is not graduation evidence, but it is
an exact falsifier of the claim that retaining a generic unfenced fallback is
harmless.

### Full overwrite: `write-file`

Creating or deliberately replacing a whole file is distinct from anchored
editing, so `write-file` remains useful. Existing-file overwrite is currently
unfenced and its success returns neither the resulting SHA nor a verification
excerpt. Clojure MCP's source demonstrates the expected invariant: an existing
file observed earlier cannot be overwritten after external change. Seon's
immutable SHA is a better local mechanism than a mutable timestamp registry.

Keep `write-file` as the one create/full-replacement owner, but require the
current SHA when the target already exists (or refuse overwrite without it),
and return the shared mutation envelope. New-file creation needs no invented
prior SHA. This closes the escape hatch that otherwise defeats anchored edit
safety.

### Observation aliases: `stat`, `file-exists?`, and `home-dir`

`file-exists?` is exactly `(:seon.agent.fs/ok? (stat req))`. It cannot
distinguish absent from denied, invalid, or unreadable, so it converts useful
recovery data into a plausible fact. `stat` should remain the one path
observation owner; remove positive eligibility from `file-exists?` and let
callers derive existence only from a successful stat envelope.

`home-dir` does not describe the grant and can point outside it. `grants`
already returns every root an agent may use, plus write and lock state. Remove
positive eligibility from `home-dir`; ordinary filesystem navigation should
begin from the capability facts, not process environment. This also eliminates
the only throwing visible function in the namespace.

### Directory discovery: `list-dir` versus `walk-dir`

These are distinct depths but overlapping discovery operations with different
entry conventions: immediate names versus recursive absolute paths. Inspect's
one `list_files` accepts a depth, demonstrating a smaller contract. This is a
lower-priority consolidation because the current names communicate their depth
and `walk-dir` adds real filter/sort/cap semantics.

After the read/edit family is measured, test one `list-dir` owner with optional
depth/glob/sort/cap and one stable path convention. Only merge if the compact
contract is smaller and the frozen discovery tasks select it more reliably;
otherwise retain both and share their entry/page shapes. Do not move content
search into this namespace: `seon.agent.search/grep` correctly owns matching
file contents while respecting the same grant.

### Result bounds and hardcoded policy

The global configured result-body cap prevents an arbitrarily large value from
rendering unbounded into later context, but several filesystem operations can
still construct a much larger value and leave the agent with a clipped,
unactionable prefix:

- `read-file` is whole-file by default and accepts an unbounded `max-lines`;
- `view` defaults to a source constant of 100 lines but also accepts an
  unbounded `max-lines`;
- ambiguous `replace!` returns every candidate and every candidate carries a
  preview; and
- a successful anchored edit's excerpt includes the whole landing range, so a
  large replacement can exceed its nominal three surrounding lines.

The filesystem also hardcodes `walk-dir`'s default 5,000 results, legacy edit
context at three lines/200 tokens, anchored preview context at three lines, and
the 100-line view default. These are runtime policy, not semantic constants.
Move the retained read/list/candidate/excerpt bounds into the existing
manifest-to-database configuration flow and resolve one immutable policy per
call. A clipped global render remains the final backstop; the operation itself
should return an honest `truncated?`/total/cursor or next-window fact so the
model can continue deliberately.

## Ranked changes by expected small-model gain

1. **P0 — one read/edit generation.** Put bounded numbered reads and the SHA
   under `read-file`; retain `replace!`, `insert!`, and fenced `write-file`;
   remove `view` and `edit-file` from the positive surface in the same
   refactor. This removes the most attractive wrong choices and their duplicate
   schema closure.
2. **P0 — one discriminated response family.** Use the shared success/failure
   envelope for reads, observations, listings, and mutations. Preserve the
   operation-specific success facts, but make `ok?` and error facts predictable
   everywhere. Success should return the facts needed for the next operation;
   failures remain bounded values.
3. **P1 — close unfenced overwrite.** Existing-target `write-file` must join
   the same SHA fence and return the resulting SHA. This prevents a model from
   bypassing a refused anchored edit with an unsafe full rewrite.
4. **P1 — remove misleading convenience facts.** Unmark `file-exists?` and
   `home-dir`; `stat` and `grants` are the exact owners and retain failure/grant
   information.
5. **P2 — measure directory consolidation.** Compare one depth-aware listing
   with the present `list-dir`/`walk-dir` pair. Keep content search in
   `seon.agent.search` and avoid a filesystem mega-function.
6. **P2 — make output policy database-derived.** Bound reads, candidate
   previews, excerpts, and walks before constructing the return value; report
   honest continuation facts and use the global render cap only as backstop.
7. **P2 — tighten remaining schema facts.** Replace `mtime :any` with one
   serializable exact representation and derive shared page/path/result shapes
   once. Then remeasure the real compact schema closure; do not optimize exact
   rendered prose or add a second shared registry.

The first implementation unit should not combine all six. P0 read/edit
convergence plus the frozen candidates below is one coherent, falsifiable
boundary. The observation and listing simplifications can follow using the
same admitted model matrix.

## Exact frozen task candidates

Each candidate is a deterministic `file_edit` generator row under the existing
native Inspect task. Setup is invocation-local workspace data; the unchanged
workspace scorer owns exact outcome correctness. Turn evidence supplies the
selected function symbols and failure envelopes for post-run classification.
The prompt never names or describes a function.

### F1 — navigate, inspect, and change one unique definition

Prompt:

> Under `{workspace}/src`, find the ClojureScript file that defines
> `tax-total`. Change that function so it sums each row's `:tax` value instead
> of its `:subtotal` value. Leave every other byte in every file unchanged.
> The edited file must parse, and `(tax-total [{:tax 2 :subtotal 20} {:tax 5
> :subtotal 50}])` must return `7`.

Setup: three `.cljs` files, only `src/report.cljs` contains the target; one
distractor contains the text `tax-total` in a comment. Oracle: all three exact
files, parse target, evaluate the stated call. This exercises grant-rooted
listing or graph/content search, bounded read, deterministic replacement, and
behavioral verification. Failure classes distinguish wrong discovery,
`read-file`/`view` choice, mutation choice, and verification failure.

### F2 — replace every occurrence without rewriting the file

Prompt:

> In `{workspace}/RELEASE.md`, change every occurrence of version `2.7.3` to
> `7.1.2`. Preserve all other bytes exactly.

Setup and oracle are the already frozen `file_edit-seed1-001` bytes. This is
the shortest selection falsifier between `edit-file`'s unique-only mode and
`replace!`'s explicit all/count behavior. Acceptance is the exact file result;
reviewed trajectory evidence records whether the model found and composed the
strong path without task coaching.

### F3 — disambiguate one repeated local anchor

Prompt:

> `{workspace}/config.edn` contains two service maps. Change only the
> `:retries` value in the map whose `:service` is `"ember"` from `3` to `6`.
> Preserve all other bytes exactly, and leave the file valid EDN.

Setup: two visually similar four-line maps, both contain `:retries 3`; the
other service is `"willow"`. Oracle: exact expected bytes plus parse. This
tests whether candidates/line windows or a wider exact anchor lead to one
deterministic edit rather than a guessed first match, global replacement, or
whole-file rewrite.

### F4 — compose inspection, insertion, and read-back

Prompt:

> Add ` :replicas 9` as a new line directly before the closing brace in
> `{workspace}/deploy.edn`. Keep the existing lines byte-identical. The result
> must remain valid EDN and contain exactly the keys `:host`, `:port`, and
> `:replicas`.

Setup and exact-file/parse oracle are the already frozen
`file_edit-seed1-004` bytes. Its exact expected content already proves the
three-key outcome; its parse check proves the result remains EDN. This
exercises bounded read, line insertion, and verification without suggesting
`insert!` or line numbers.

## Comparison and acceptance

Run F1–F4 serially through the admitted local model callback after the current
dependency and ACME source gates reopen. Freeze prompt/setup/oracle bytes before
the first surface change and use identical model/server/config/database policy
for before and after arms. Do not count an infrastructure failure as model
failure.

The unit is accepted when:

- a fixed database value renders the same remaining function contracts and
  schema forms byte-identically on repeat;
- the positive inventory has one read path and no visible unfenced legacy edit
  path;
- every visible filesystem function returns the common discriminated envelope
  and no semantic failure throws into the agent loop;
- existing-target full overwrite cannot bypass the SHA fence;
- focused filesystem/matcher/context tests pass;
- F1–F4 exact workspace outcomes improve without adding tool names or recovery
  instructions to task text; and
- native `.eval` review classifies selection, argument, envelope, recovery,
  and verification failures from retained forms/results rather than narration.

The final gate remains the frozen representative Inspect suite and its
filesystem/shell/web category floor. A smaller card alone is not success; the
small model must navigate, compose, mutate, and verify the correct workspace.
