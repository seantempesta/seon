---
type: research
status: complete
tags: [research, agent, context, render, ai, sci]
---

# Strict dogfood audit — 2026-08-12

## Question and authority

This read-only production audit asks whether every agent-facing context byte
traces to the schema-derived neighborhood walk, declared render functions, and
executed receipts. It applies ruling 28 in
[`self-generating-context-prd-2026-08-11.md`](../plan/self-generating-context-prd-2026-08-11.md):
no manually assembled context anywhere. I read that PRD end to end before the
source sweep, along with the current program boundary, the context architecture,
the `data-oriented-clojure` workflow, and the `llm-providers` workflow.

The requested “ruling 7 injection” classification refers to ruling 7 in
[`agent-interface-economy-2026-08-10.md`](../plan/agent-interface-economy-2026-08-10.md):
no per-namespace obligations; injected-but-honest session forms are acceptable.
Ruling 7 in the named self-generating-context PRD is instead the requirement
that both surfaces be beautiful and lean. This report uses the requested
classification **(b)** with the former ruling's exact meaning.

Audit snapshot: `db67d8ab17298cbfe4d5aaf7312a8200433b4c27`, with uncommitted
foreign churn visible in `src/seon/bootstrap.clj`, `src/seon/cluster/agent.clj`,
`src/seon/cluster/loop.clj`, `src/seon/render.clj`, `src/seon/render/web.clj`,
`resources/seon/schemas/seon.cluster.agent.edn`, and
`resources/seon/schemas/seon.cluster.run.edn`. The bootstrap/run change was
actively adding generated openings, trigger, and turn-budget facts; the render
change was independently adding a profile and compiled pull-plan cache. Shared
HEAD then advanced to `467eaac44` while the audit was in progress, including
the rulings 32–34 landing and render pull-cache commit `2e814eec1`. I preserved
the named snapshot and inspected the visible current source where cited; I did
not re-scope into those live owners or make a production edit.

## Verdict

The provider's ordinary primary prompt path is already strict: one render-proc
history result is captured and handed unchanged to `seon.ai`. The whole tree is
not yet strict. There are eight ruling-28 violation classes at current source:

1. the legacy hand-authored bootstrap resource and stored form plan remain the
   active fresh-agent seed;
2. failover adds an uncaptured provider-only system message;
3. the agent HTML projection still invokes the retired transcript assembler;
4. that assembler fabricates comment-only/no-receipt and undisposed-run
   entries;
5. the public walk writes comment headers, guidance, and volatile metadata
   outside declared render entries; and
6. effect status is appended as a prompt-only prose tail instead of reached
   effect receipts;
7. the provider history serializes declared form/value projections without
   executing the forms or reading receipts; and
8. the SCI base context keeps an unexplained manual callable roster beyond the
   three acceptable bare injections.

Five new issue notes record the unfiled classes. Three existing issue notes
now carry current line evidence for the already-filed walk/effect classes.

## Provider prompt path

| Class | Current evidence | Judgment |
|---|---|---|
| **(c) clean** | `src/seon/cluster/prompt.clj:155-200` asks the render proc for an acquired context, applies only a token-budget decision, and returns the exact `:seon.cluster.prompt/text`. `:147-153` records a digest/token contribution but adds no context text. | `seon.cluster.prompt` has no preamble, header, system-slot prose, joiner prose, or formatting scaffold. Its error sentences are refusal values and logs, not provider context. |
| **(c) clean** | `src/seon/render/web.clj:991-1044` joins only retained `:seon.render.history/bytes`; a refresh obtains entries from `seon.render.walk/history`. | The blank-line join is structural serialization of retained entries, not authored prose. Unchanged acquisition returns those bytes without re-rendering. |
| **(c) clean** | `src/seon/cluster/loop.clj:1180-1203` commits the rendered context before the call and extracts exactly its text. `:1219-1223` passes that text as `:seon.ai/prompt`. | Primary prompt capture and handoff obey one-value identity. |
| **(c) clean** | `src/seon/ai.clj:625-663` emits provider protocol fields and role/content maps. The primary has one user-role content value; role names, JSON keys, and `stream_options` are external protocol structure rather than agent-facing prose. | No provider preamble or instruction text is authored in `seon.ai`. |
| **(a) violates ruling 28** | `src/seon/cluster/loop.clj:1218-1223` optionally adds `:seon.ai/system`. `:1261-1281` renders the primary failure after capture and recurs with that separately assembled string. `src/seon/ai.clj:637-640` sends it as a second context message. | The backup's full context is not the captured prompt and never passes through the retained walk history. Filed [`failover-adds-an-uncaptured-system-context-fragment.md`](../../../seon/issues/failover-adds-an-uncaptured-system-context-fragment.md). |
| **(c) clean** | `src/seon/ai.clj:1209-1222` serializes the provider document exactly once and records the identical JSON body. | Serialization is an external wire boundary and does not fabricate context. |

## String-building sweep near prompt, context, and history

The sweep covered all `src/**/*.{clj,cljc}` prompt/context/transcript matches,
then every `str`, `str/join`, and `format` in the owning prompt, context,
history, reply, run, and effect namespaces. The findings below distinguish
content assembly from diagnostics, HTML, protocol encoding, and exact receipt
serialization.

| Class | Current evidence | Judgment |
|---|---|---|
| **(a) violates ruling 28** | `resources/seon/bootstrap.edn:1-70` manually lists the opening preamble, forms, example, and completion. `src/seon/bootstrap.clj:82-114,343-377,379-426,451-513` reads, persists, substitutes, digests, and freezes that plan. | Ruling 24 explicitly deletes both `bootstrap.edn` and the stored plan; a generated episode beside this active path does not convert it. Filed [`generated-opening-still-reads-a-hand-authored-bootstrap-plan.md`](../../../seon/issues/generated-opening-still-reads-a-hand-authored-bootstrap-plan.md). The live bootstrap lane owns the cut; this audit did not touch its files. |
| **(c) clean** | `src/seon/cluster/agent.clj:114-156` declares the root comment plus `(help)` form entry and renders the situation value through the agent schema at `resources/seon/schemas/seon.cluster.agent.edn:1-9,92-117`. | The orientation prose is owned by a declared render function over live situation data, exactly ruling 25's allowed shape. |
| **(a) violates ruling 28** | `src/seon/render/walk.clj:741-785` pairs a declared form render and declared AI render for one reached unit and serializes them as `namespace=> form` plus printed value. `:787-808` returns those pairs without any receipt read or execution join, and `src/seon/render/web.clj:997-1044` sends them to the provider. | The bytes simulate a settled REPL exchange before the declared form has executed. Filed [`render-history-serializes-unexecuted-form-projections.md`](../../../seon/issues/render-history-serializes-unexecuted-form-projections.md). |
| **(c) clean** | `src/seon/render/web.clj:972-993` appends entries by call identity plus basis and joins their retained bytes. | It assembles history structure, not prose. |
| **(a) violates ruling 28** | `src/seon/render/walk.clj:549-652` creates `;; d`, a synthetic walk header, branch guidance, an elision summary, and a volatile-metadata marker. `src/seon/render.clj:671-685,777-785` adds a separately authored REPL-state suffix and textual failure wrapper. | These strings are not the form/value pair of a reached unit or an executed receipt. Existing issues updated: [`render-walk-frames-values-as-comments.md`](../../../seon/issues/render-walk-frames-values-as-comments.md) and [`render-walk-wrapper-returns-comment-notices.md`](../../../seon/issues/render-walk-wrapper-returns-comment-notices.md). This path is the public `seon.render/walk` value rather than the render proc's provider-history path, but ruling 28 says every context surface. |
| **(a) violates ruling 28** | `src/seon/effect.clj:690-779` queries effect state and writes a fixed background-work instruction plus pending/result/duration prose; `src/seon/render/walk.clj:643-650` appends it as a suffix. | The facts already have renderable identities, so a prompt-only tail is a second assembly path. Existing issue updated: [`effect-context-suffix-returns-comment-notices.md`](../../../seon/issues/effect-context-suffix-returns-comment-notices.md). |
| **(c) clean** | `src/seon/cluster/loop.clj:1410-1427` joins def-restore notices only into `:seon.sci.eval/output-prefix` for the first executed form. `src/seon/sci/eval.clj:1410-1515` derives the notices from the selected agent's def facts. | The notice becomes executed receipt output rather than an unrecorded prompt injection. It is receipt-backed, though its presentation quality remains separately reviewable. |
| **(c) clean** | `src/seon/cluster/reply.clj:81-117,224-266,303-335` strips Markdown fences and preserves model-authored prose as source comments attached to an executable form. | This transforms provider output into the exact run source; it does not assemble provider input. Its regexes are existing reply-reader policy and outside this ruling-28 finding. |
| **(c) clean** | `src/seon/context.clj:108-188` omits capture facts from AI, renders captured bytes only for HTML debug, and stores the exact prompt plus contribution evidence. | Capture is observability, not another content producer. |
| **(c) clean** | `src/seon/render/web.clj:513-541` reads the exact latest capture for the debug AI pane. The “No recorded context capture” fallback is human-only page state. | No agent receives the fallback. |

`format` and remaining `str/join` uses in `seon.ai` are model-registry render
faces, HTTP error evidence, usage/price display, or transport cause chains
(`src/seon/ai.clj:181-288,672-678,1105-1127,1300-1311`). They do not feed
`:seon.ai/prompt` or `:seon.ai/system` and are clean for this audit.

## Complete SCI injection census

SCI is pinned at `fcbd8862800e638dc0f8f5521111f999279cbcd2`.
`sci/copy-var*` copies the host Var's root and metadata into an SCI Var
(`reference-code/sci/src/sci/core.cljc:112-137`), while
`sci/add-namespace!` merges bindings into the context namespace map
(`reference-code/sci/src/sci/core.cljc:679-684`). These sites affect what is
callable; they do not themselves add bytes to the prompt. Ruling 20 permits
every program-graph function to be callable. Ruling 7 permits injected session
operations without forcing every namespace to define them, provided their
presence is honestly visible in the generated situation/history.

### Initial namespace map and copied Vars

| Class | Injection site | Exact injected scope | Situation/render explanation |
|---|---|---|---|
| **(c) clean infrastructure, plus (a) unexplained roster** | `src/seon/sci/eval.clj:209-216` | Interrupt-aware `clojure.core` and `clojure.string`; `seon.schema/register!` and `unregister!`. | The interrupt-aware standard namespaces are interpreter infrastructure. The two schema functions are a manual special roster not explained at `src/seon/cluster/agent.clj:140-156`; they are covered by [`sci-base-context-silently-hand-lists-special-callables.md`](../../../seon/issues/sci-base-context-silently-hand-lists-special-callables.md). |
| **(a) violates ruling 28** | `src/seon/sci/eval.clj:217-223` | Every current `clojure.test` public, derived from `ns-publics`, copied with `copy-var*`. | The member set is derived, but it is a special host injection outside program acquisition and the situation render does not name or explain it. Same new SCI-base issue. |
| **(a) violates ruling 28, except the explained `my.run` pair** | `src/seon/sci/eval.clj:224-241` | `my.run/wait`, `my.run/complete`; `my.background/background`, `poll`, `await`; `my.message/send`, `decline`. | `my.run/complete` and `wait` are explicitly explained at `src/seon/cluster/agent.clj:155-156` and qualify as **(b)**. The background and message operations are not displayed or explained by that render; the situation value's undisplayed protocol-namespace vector is not an explanation. Same new SCI-base issue. |
| **(c) clean** | `src/seon/sci/eval.clj:242-248` | `Throwable` and `Error` under qualified and unqualified class symbols. | Host classes are execution bindings, not functions or context content. |
| **(b) acceptable ruling-7 injection** | `src/seon/sci/eval.clj:249-264` | Bare `help`, `dir`, and `doc` in `clojure.core`, plus the qualified `seon.bootstrap` namespace. | `src/seon/cluster/agent.clj:138-154` explicitly says “Injected callables” and derives each one-line description from the Var's doc. `(help)` is also the agent root's declared form at `resources/seon/schemas/seon.cluster.agent.edn:8-9,94-97`. |

### Later `add-namespace!` sites

| Class | Injection site | Exact effect | Judgment |
|---|---|---|---|
| **(c) clean** | `src/seon/sci/eval.clj:901-968` | For each core-provenanced first-party namespace, install every public host Var plus every indexed private function Var; refer targets get forwarding Vars. | Namespace membership and function identity are database program facts. This implements universal callability and injects no prompt fragment. |
| **(c) clean (dead helper)** | `src/seon/sci/eval.clj:970-978` | `install-host-namespace!` would copy an arbitrary host intern map. | No current caller exists. It is not an active injection path; its dead-code status is outside this context audit. |
| **(b) acceptable ruling-7 injection** | `src/seon/sci/eval.clj:980-1165` | Replace `clojure.repl/doc` and `dir`, then expose the same row-derived macros bare through `clojure.core`. | The documentation value is derived from program rows. The situation render explicitly names bare `dir` and `doc` at `src/seon/cluster/agent.clj:150-152`. |
| **(c) clean** | `src/seon/sci/eval.clj:1355-1408` | Install declared classes, namespace aliases/imports/refers, agent-authored functions, and tests in dependency order. | All membership comes from acquired namespace/function/test facts; no hand list or context text is created. |
| **(c) clean** | `src/seon/sci/eval.clj:1441-1515` | On a turn fork, create only missing namespaces and install the selected agent's persisted defs/atoms/unrestorable values. | The inputs are agent-scoped def facts. Failures become receipt output through the loop, not silent prompt prose. |

No other `sci/copy-var`, `sci/copy-var*`, or `sci/add-namespace!` call exists
under `src/`. `sci/install-namespace-bindings!` and `sci/install-var-roots!`
were also inspected: their inputs at `src/seon/sci/eval.clj:1355-1408` and
`:1503-1513` are acquired program rows or selected def facts, not hidden
rosters.

## Reader, renderer, and transcript remnant sweep

| Class | Current evidence | Judgment |
|---|---|---|
| **(a) violates ruling 28** | `resources/seon/schemas/seon.cluster.agent.edn:1-9` routes the agent HTML projection to `seon.render.transcript/render-session-html`, while AI uses the situation render. `src/seon/render/transcript.clj:127-259,489-515,879-979` retains an independent query, candidate, ordering, fit, and assembly mechanism. | The old transcript assembler survived W2 and remains active on the human twin. Filed [`agent-html-still-uses-the-retired-transcript-assembler.md`](../../../seon/issues/agent-html-still-uses-the-retired-transcript-assembler.md). |
| **(a) violates ruling 28** | `src/seon/render/transcript.clj:127-160` selects comment-only form rows specifically when no matching receipt exists. `:203-259` promotes them into its kind roster. | These entries are not backed by executed receipts. Same new issue. |
| **(a) violates ruling 28** | `src/seon/render/transcript.clj:436-443,637-669,879-927` fabricates an undisposed-run display as `system=> (db/pull ...)`, despite no such form or receipt existing. | A truth-preserving declared run render is allowed; inventing a REPL form that was never executed is not. Same new issue. |
| **(c) clean** | `src/seon/render/transcript.clj:389-413,859-870` uses exact form source plus the associated receipt's result/error/output when constructing a receipt entry. | This receipt-backed subset is strict in content provenance, though it lives inside a superseded second assembler. |
| **(c) clean** | `src/seon/sci/reader.cljc:390-567` derives declaration and namespace facts from parsed forms; `src/seon/cluster/reply.clj:129-266` freezes the reader's exact source spans. | The reader does not fabricate historical entries or results. |
| **(c) clean** | `src/seon/eval/drive.clj:287-298` calls the old transcript renderer only to return diagnostic drive output. | It is not a provider-context caller, but it keeps the retired mechanism reachable and should disappear with the new assembler issue's acceptance. |
| **(c) clean** | `src/seon/render/web.clj:284` calls only `transcript/reasoning-disclosure`; reasoning joins the HTML page after the shared context-fit decision. | This helper does not assemble agent prompt history. |

## Issue ledger

New blocker notes and index rows:

- [`generated-opening-still-reads-a-hand-authored-bootstrap-plan.md`](../../../seon/issues/generated-opening-still-reads-a-hand-authored-bootstrap-plan.md)
- [`failover-adds-an-uncaptured-system-context-fragment.md`](../../../seon/issues/failover-adds-an-uncaptured-system-context-fragment.md)
- [`agent-html-still-uses-the-retired-transcript-assembler.md`](../../../seon/issues/agent-html-still-uses-the-retired-transcript-assembler.md)
- [`render-history-serializes-unexecuted-form-projections.md`](../../../seon/issues/render-history-serializes-unexecuted-form-projections.md)
- [`sci-base-context-silently-hand-lists-special-callables.md`](../../../seon/issues/sci-base-context-silently-hand-lists-special-callables.md)

Existing notes updated with current evidence:

- [`render-walk-frames-values-as-comments.md`](../../../seon/issues/render-walk-frames-values-as-comments.md)
- [`render-walk-wrapper-returns-comment-notices.md`](../../../seon/issues/render-walk-wrapper-returns-comment-notices.md)
- [`effect-context-suffix-returns-comment-notices.md`](../../../seon/issues/effect-context-suffix-returns-comment-notices.md)

## Calibration — what is genuinely already strict

The strict parts are substantial and define the surviving spine:

- `seon.cluster.prompt` no longer owns any context prose or block roster. It
  acquires one retained history, checks its budget, and returns exact bytes.
- Primary provider construction has no hidden preamble. The role/content maps
  are protocol structure, and the captured prompt is the only primary context
  value.
- The render proc owns append-only entry identity and byte retention. Its
  `history-text` function joins already serialized entries without restating
  them.
- The new `seon.render.walk/history` derives form and AI projections from the
  same pull member and excludes the root from generic duplication. That
  acquisition and pairing is strict; the missing execution/receipt join is the
  filed blocker, so these bytes are not yet a strict REPL history.
- `(help)` is now an ordinary declared root form over a live situation value;
  the orientation prose is its schema-declared AI render, not prompt-builder
  text.
- Exact captures precede provider calls and remain the forensic truth. The
  debug pane reads those stored bytes rather than rebuilding a prompt.
- SCI's broad first-party callable scope is derived from the program graph.
  The special bare session operations `help`, `dir`, and `doc` are explicitly
  named by the situation render. The additional base-context roster is filed
  rather than counted as strict.
- The agent's defs restore path reads facts and installs values; it does not
  replay authored source. Any restore notice enters an executed receipt.

The remaining work is deletion and convergence, not invention: remove the
legacy bootstrap and transcript owners, route failover/error/effect observations
through the retained history, execute generated forms before rendering their
receipts, derive or explain the special SCI base bindings, and delete the public
walk's extra prose frame.
