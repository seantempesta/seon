---
type: research
status: active
tags: [research, agent]
---

# Tool designs — readiness eval for shell / python / web / blob / editor (2026-07-02)

> Purpose: the inspect.ai benchmark suite needs the seon agent to have shell,
> python, and web-fetch/browser capability fns; `my.blob` is wanted as the
> content-addressed disk tier (and the substrate for always-on turn/prompt
> capture). This doc FINDS every existing design/research artifact for those
> tools, EVALUATES each for build-readiness against the house template
> (`seon.agent.fs` / `seon.agent.search`), and recommends a build order. It
> also summarizes the case-2 execution-substrate decision (pod-in-Docker vs
> `sandbox().exec` round-trip).

## TL;DR

- **Shell is build-ready today.** A complete, decision-closed design exists
  (`research/tool-my-shell-research.md` + the consolidated verdict in
  `archive/tool-design-decisions.md`): clone `seon.agent.search.internal`'s
  `execFile` wrapper, reuse the `seon.agent.fs` cwd gate, `SEON_SHELL`
  default-deny, full request/response schemas written, the one spec refinement
  (`ok?` = "RAN", not "exit 0") already decided. Zero npm deps. ~1 day.
- **Blob is design-complete but decision-thin.** Two converging specs —
  `toolkit.md` §`my.blob` (verb sketch) and the fuller
  `docs/seon/architecture/observability.md` (content-addressed, cluster-dir,
  blob-ref-as-data with token estimate + media hint, `put!`/`get`/`text`,
  one-store-many-writers incl. `:seon.agent.turn/prompt-blob` turn capture).
  No registered schemas yet; hash algo, compression, size threshold, and GC
  are open. ~1–2 days including the turn-capture writer.
- **Python is a sketch riding on shell.** The only design is the inspect
  spike's §5e (`my.py/run` — source shipped as stdin DATA, never
  shell-concatenated). Once `seon.agent.shell` exists, `my.py/run` is a thin
  stdin-mode wrapper. ~0.5 day after shell.
- **Web-fetch has NO written design; browser has none at all.** The inspect
  spike (§5d) names an `http`/fetch fn as a required `seon.agent.fs`-sibling
  and the benchmarks survey shows GAIA/AssistantBench need web reach, but no
  schemas, no gating posture (`SEON_WEB`?), no readability/paging decisions
  exist. Fetch is a ~1-day design+build following the house template; a real
  browser (WebArena-class) is out of lane per the survey.
- **In-place file editor: no design exists anywhere.** The toolkit thesis is
  explicitly "the agent does not edit files; it defines fns in the REPL"
  (`toolkit.md` TL;DR) — an editor was never specced. SWE-bench-style inspect
  tasks would need one (`edit-file` old-string/new-string on the fs floor);
  it is a deliberate gap requiring an owner call, not an oversight.
- **Substrate (case-2): recommendation recorded, owner has NOT decided.** The
  spike (§5c/§5d) weighs (1) pod-in-sandbox (Docker/mvm; airtight, local fns,
  boot-per-sample cost) vs (2) `sandbox().exec` round-trip (simpler infra,
  host-escape risk + per-call latency). The spike + the Gemini skeptical
  review both recommend **pod-in-sandbox**, with ONE fn-contract library so
  case-1 and case-2 differ only in the adapter. Explicitly flagged "needs a
  decision before case-2 build".

## Readiness table

| Tool | Design exists? | Schemas written? | Gating decided? | Open questions | Build size |
|---|---|---|---|---|---|
| `my.shell` (`seon.agent.shell` floor) | YES — full research note + consolidated decision | YES — full `:seon.shell/*` request/response in the research note + `toolkit.md` | YES — `SEON_SHELL` default-deny; cwd via `seon.agent.fs/stat`; argv-only | none blocking (SIGKILL escalation + process-tree reaping noted as v2) | S (~1 day; new floor ns + `my.*` wrapper, clones `search.internal`) |
| `my.py` (python passthrough) | SKETCH — inspect spike §5e only | NO (`:my.py/source`/`stdout`/`stderr`/`exit` named, unregistered) | inherits shell's gate (it IS a shell invocation) | temp-file vs stdin (`python -`) mode; venv/interpreter selection; timeout default | XS (~0.5 day AFTER shell) |
| web-fetch (`seon.agent.http`?) | NO — named as a needed fs-sibling in spike §5d only | NO | NO — grant name, allow/deny domains, response caps all undecided | gate posture; HTML→text readability; paging/honest-truncation shape; redirects/timeouts | S–M (~1 day design + build; `js/fetch` is native to the pod) |
| browser (WebVoyager/WebArena-class) | NONE | NO | NO | whether to do it at all — survey scopes GUI/computer-use "out of lane" | L — recommend DEFER |
| `my.blob` (`seon.blob` floor) | YES ×2 — `toolkit.md` §`my.blob` + `docs/seon/architecture/observability.md` | NO (verbs + ref-shape described, zero `register!` calls) | PARTIAL — cluster-dir location settled; no env gate discussed (likely none needed: it never leaves the cluster dir) | hash algo (SHA-256 per embeddings precedent), zstd availability in the pod's Node, datom-vs-blob size threshold, GC/retention, `text` paging shape | S–M (~1–2 days incl. the always-on turn-capture writer) |
| in-place editor (`edit-file`) | NONE | NO | would inherit `SEON_FS_*` + `read-only?` | whether it belongs at all (vs whole-file `write-file` + paged reads); old/new-string vs line-range semantics | S (~0.5–1 day once sanctioned; a `seon.agent.fs` sibling verb) |

## Per-tool detail

### 1. Shell — `my.shell` over a new `seon.agent.shell` floor

**Sources:**

- `docs/prds/agent-fsm/research/tool-my-shell-research.md` — the full design.
- `docs/prds/agent-fsm/archive/tool-design-decisions.md` — the consolidated
  decision table (row `my.shell`) + build order.
- `docs/prds/agent-fsm/toolkit.md` §`my.shell` — the catalog spec with the
  registered-schema sketch and the ~1.2k-tok render budget.

**Completeness — the strongest of the five.** Every decision is closed:

- **Engine:** Node builtin `child_process.execFile`, Promisified to
  ALWAYS-resolve, cloned byte-for-shape from `seon.agent.search.internal`
  (`src/seon/agent/search/internal.cljs`) — timeout SIGTERM, `maxBuffer` cap
  with honest `truncated?`, `windowsHide`, error-object classification
  (`killed` → timed-out, `ENOENT` → can't-run,
  `ERR_CHILD_PROCESS_STDIO_MAXBUFFER` → truncated-but-delivered).
- **Rejected libraries with reasons:** `execa` (ESM-only since v6 vs the CJS
  shadow bundle; throws by default), `babashka.process` (JVM-only; its
  data-map API is the design muse). Net-zero npm deps.
- **Schemas:** full `:seon.shell/run-request` / `run-response` written out in
  the research note (cmd/args/cwd/stdin/timeout-ms in;
  exit/out/err/timed-out?/truncated? out), referencing the shared
  `:seon.path/abs` and `:seon.error/*` backbone shapes.
- **The one spec refinement (decided):** `ok?` means "the process RAN", NOT
  "exit 0" — a non-zero exit is a legitimate answer (mirrors
  rg-exit-1-is-no-matches in `seon.agent.search`). `toolkit.md`'s older
  `ok? = exit 0` sketch is superseded by the research note on this point.
- **Gating:** argv-only (no `sh -c` injection surface); cwd gated through
  `seon.agent.fs/stat` (reused, never reimplemented); the whole verb
  default-deny behind a `SEON_SHELL` host grant, same posture as `SEON_FS_*`.
  Soft boundary against LLM accidents, not a security boundary.
- **Known v2 items (noted, non-blocking):** stdin needs one extra line
  (`child.stdin.write` — `execFile` has no `input` opt), timeout exit
  sentinel 143, SIGTERM may not reap process trees (switch to `spawn` +
  process-group kill if it ever bites).

**Conflicts with newer work:** none. The inspect spike §5d independently
re-derives the same conclusion ("design the `exec`/`http` fns as
`seon.agent.*` siblings of `seon.agent.fs`"). `core-tools-status-2026-06-28.md`
confirms `my.shell` is "NOT built" — spec-only. `src/seon/agent/shell.cljs`
does not exist (verified).

**Remains to decide:** nothing blocking. Build it.

### 2. Python — `my.py/run`

**Sources:**

- `docs/prds/agent-fsm/research/inspect-seon-bridge-spike-2026-07-01.md` §5e
  ("Python passthrough — a `my.py/run` capability fn (source stays DATA)").

**Completeness — a correct contract sketch, nothing more.** The load-bearing
rule is written: the fn ships Python source to the interpreter **as
stdin/argument DATA, never string-concatenated into a shell line** (the same
rule as `seon.web.reactive.call`'s "args stay DATA"). Return shape named:
`{:my.py/stdout :my.py/stderr :my.py/exit}`. Under inspect+Docker it pipes to
the sandbox's `python`; standalone, to local `python` — same contract, the
adapter swaps the backend.

**Not designed yet:** no registered schemas; no decision on `python -` stdin
vs temp-file; no interpreter/venv selection knob (the box's default is 3.9,
inspect needed a 3.12 venv — an agent-driven py task will hit the same); no
timeout default distinct from shell's. There is no `tool-my-py-research.md`.

**Dependency:** `my.py/run` is a thin specialization of `seon.agent.shell`
(cmd `python`, args `["-"]`, source as stdin). Build shell first; python
follows in half a day. It does NOT need its own env gate — `SEON_SHELL`
covers it (running python IS running a command).

### 3. Web-fetch / browser

**Sources (all indirect — no dedicated design doc exists):**

- `docs/prds/agent-fsm/research/inspect-seon-bridge-spike-2026-07-01.md` §5d —
  names "an `http`/fetch fn" as one of the missing capabilities to build "using
  the SAME house rules" as `seon.agent.fs`, and "maybe web-search" in §5b.
- `docs/prds/agent-fsm/research/agentic-benchmarks-survey-2026-06-26.md` —
  GAIA ("tools + files, real web") and AssistantBench ("live web") need web
  reach; the survey scopes "GUI/computer-use out of lane" (WebArena /
  WebVoyager / BrowserGym class).
- `docs/prds/agent-fsm/research/clojure-idioms-for-agents-2026-06-28.md` —
  agents already have raw `js/fetch` via interop (the pod is a Node process);
  what's missing is the gated, enveloped, honest-paging capability fn.

**Completeness — a name and a posture, no design.** Nobody has decided:

- The grant (`SEON_WEB`? an allow/deny domain list? default-deny like
  `SEON_FS_*` is the obvious house answer but is unwritten).
- The response shape: raw body vs HTML→readable-text extraction, a byte/token
  cap with honest `truncated?`, paging (`from-line`/`max-lines` like
  `read-file`, or blob-the-body + return a projection — a natural `my.blob`
  composition).
- Redirect/timeout/content-type handling; whether a `web-search` verb (an API
  key) ships alongside fetch.

**Recommendation:** treat fetch as a `seon.agent.http` floor + `my.web` (or
`my.http`) wrapper designed by direct analogy to `seon.agent.search/grep`
(one `^:async` verb, envelope, cap + hint). The engine is native `js/fetch` —
zero deps. A full browser is a separate, large decision; the survey already
scopes it out of lane and GAIA-level-1 is reachable with fetch + shell +
files. **Defer browser; design+build fetch (~1 day).**

### 4. Blob — `my.blob` over a `seon.blob` floor

**Sources:**

- `docs/prds/agent-fsm/toolkit.md` §`my.blob` — the catalog entry: protected
  `seon.blob` floor, "content-addressed, on-disk zstd", thin `put!`/`get`
  wrapper storing the hash on a typed projection entity; ~800-tok budget.
- `docs/seon/architecture/observability.md` — the fuller (newer) target
  design: content-addressed names (write-idempotent, dedup for free), blobs
  under the cluster dir beside the store; **a blob ref is data** (hash +
  token estimate + media hint on the datom); verbs `put!` / `get` / `text`
  (paged, honest totals) on the standard envelope; **one store, many
  writers** — always-on turn capture (`:seon.agent.turn/prompt-blob`, the
  raw LLM reply past a size threshold), oversized eval results, and
  agent-authored artifacts; blobs inside the `my.search` grep surface and
  pointable at the embedding index. "Datom vs blob is decided by size, never
  by kind."
- `docs/prds/agent-fsm/archive/tool-design-decisions.md` — `my.blob`
  explicitly DEFERRED from the eight researched tools ("spec the seam, build
  with the first real need" — echoed in `tool-my-recall-research.md`).
- `docs/prds/agent-fsm/research/result-paging-design-2026-06-27.md` §option 6
  — considered blob-the-full-text for oversized eval results; chose the
  live-var re-derive for that use, keeping blob for durable content.
- `docs/prds/embeddings/batched-cache-archive-design-2026-06-25.md` — the
  cousin precedent: an append-only content-addressed archive keyed by
  SHA-256, already designed for the embedding tier (hash-algo precedent).
- `docs/seon/architecture/data-model.md` — existing turn-capture projections
  (`:seon.agent.turn/prompt-chars`, `/prompt-file`) that the blob ref
  supersedes/absorbs.

**Completeness — the target behavior is fully described across two docs; the
schemas and floor mechanics are not.** Nothing is registered
(`src/seon/blob.cljs` does not exist — verified); the roadmap's build-path
note in `observability.md` says "blob store spec-only". The first real need
has now arrived twice over: (a) always-on turn/prompt capture, (b) benchmark
run outputs / scraped documents for the inspect suite.

**Remains to decide (small, but decide before building):**

- **Hash:** SHA-256 (follow the embeddings archive precedent; Node
  `crypto.createHash` — no dep).
- **Compression:** `toolkit.md` says zstd — verify the pod's Node version
  exposes `node:zlib` zstd (added in Node 22.15+/23.x); otherwise gzip is the
  zero-risk fallback. Don't add an npm dep for this.
- **The datom-vs-blob size threshold** (one constant, registered once).
- **GC/retention:** content-addressed + append-only means deletion is a
  policy question; v1 can ship with no GC (the embeddings archive made the
  same call) but say so.
- **Gating:** no env grant seems needed — the floor writes only under the
  cluster dir (not agent-chosen paths), so it is not an fs-allowlist surface.
  Confirm that framing at review.

### 5. In-place file editor

**Sources:** none. Searched `toolkit.md`, all `tool-my-*-research.md`, the
archive, and the research folder for editor/patch/str-replace designs — the
only "edit" verbs in the corpus are `my.code/forget!` (program-graph, not
files) and `seon.agent.fs/write-file` (whole-file overwrite).

**Why the gap is deliberate:** the toolkit thesis (`toolkit.md` TL;DR) is
"Seon's agent does not edit files; it DEFINES functions, evals them,
redefines (= upserts) … in a live REPL". File editing was consciously left
out of the eight-tool research pass.

**Why it now matters:** SWE-bench-style / GAIA file-manipulation inspect
tasks score an EDIT to an existing file. Whole-file `write-file` forces the
agent to re-emit entire files (token-expensive, error-prone on big files).
The natural design is a `seon.agent.fs/edit-file` sibling verb —
old-string/new-string exact replacement (the Claude Code `Edit` model),
unique-match-required, gated by the existing allowlist + `read-only?`,
returning the standard envelope with an honest replaced-count. It composes
with paged `read-file` (read the region, edit the string).

**Remains to decide:** whether to sanction it at all (owner call — it bends
the "no file editing" thesis for benchmark parity), and the matching
semantics (exact-string vs line-range). Once sanctioned it is a half-day
verb on the existing floor.

## The house template (what any builder must follow)

From `src/seon/agent/fs.cljs` and `src/seon/agent/search.cljs` (+ their
`internal` siblings) — the two built floors every new capability clones:

- **Facade/floor split.** The public ns holds schemas + verbs; ALL plumbing
  (hard caps, env reads, npm/syscall boundary, envelope helpers, allowlist
  gate) lives in `<ns>.internal`. The floor is `:core-seed`-guarded; the
  `my.*` wrapper is the editable `:toolkit-seed` layer.
- **Schema-first, map-in/map-out.** Every scalar registered
  (`schema/register!`), then named `::<verb>-request` / `::<verb>-response`
  maps; every public fn carries `{:malli/schema [:=> [:cat ::req] ::resp]}`.
  Optional = absent, never nil.
- **Errors are values — NEVER throw.** Every response carries the ns-local
  `::ok?` discriminator; failure = `{::ok? false ::error <guiding message>}`
  (search adds `::raw-error` for the npm-side detail). `^:async` fns always
  RESOLVE, never reject. Note the newer convention (`toolkit.md` RESULT
  backbone + the shell research): failures should move to the shared
  `:seon.error/*` map (`:seon.error/message` + `:seon.error/kind`) rather
  than the bare string the older fs/search floors used — new floors should
  start there.
- **Capability-gated, default-deny.** Access via an explicit host grant
  (`SEON_FS_ROOT` / `SEON_FS_LOCK`; `SEON_SHELL` next), inspectable via a
  `grants` verb, adjustable via `configure!` unless host-locked. Path reach
  is ALWAYS delegated to `seon.agent.fs` (`stat` / `out-of-scope?`) — never
  reimplemented. A soft boundary against LLM accidents, not security.
- **Honest paging/truncation.** Partial results never look complete:
  `read-file` returns `from-line`/`lines-returned`/`total-lines`; walk/grep
  return `total-found`/`match-count` + `truncated?` + a narrowing `hint`;
  grep's concise default is grouped `by-file` rows with the flat list behind
  `:full? true` (the post-token-audit shape from
  `core-tools-status-2026-06-28.md`).
- **Sync vs async is load-bearing** (`archive/tool-design-decisions.md` §D):
  `seon.agent.fs` is SYNC and must stay so (auto-await fires only on a form's
  outermost value); anything that awaits a child process / wire write is
  `^:async`.
- **Docstrings render into agent context** — worked examples in the ns
  docstring, line-1 ≤72-char sentence, composability recipe named (a grep
  hit's absolute path feeds `read-file` with no guessing).

## The case-2 execution substrate — options, recommendation, decision status

Context: case-1 (memory/QA/niah — agent uses its own DB fns) is DONE and
needs no substrate. Case-2 (GAIA-level-1, code-in-sandbox — agent acts on a
filesystem/shell/web) needs to decide WHERE the capability fns' side effects
execute. All from
`docs/prds/agent-fsm/research/inspect-seon-bridge-spike-2026-07-01.md`
(§5b–§5e) + `docs/prds/agent-fsm/research/inspect-ai-harness-deep-dive-2026-06-30.md`
+ `docs/prds/agent-fsm/research/microvm-isolation-experiment.md`:

- **Option 1 — pod-in-sandbox** (run the whole pod inside inspect's Docker
  sandbox, or an mvm microVM): fns do LOCAL `child_process`/`fs` inside the
  container; the bridge just boots the pod in the box and `configure!`s
  fs+shell roots to the container's own filesystem. Airtight isolation
  (also solves the JS-runtime-state isolation §5e names), native speed, no
  per-call HTTP. Cost: a pod image + boot-per-sample.
- **Option 2 — `sandbox().exec` round-trip**: pod stays on host; each
  capability fn HTTP-calls inspect's `SandboxEnvironment.exec`. Simpler
  infra, but the Gemini skeptical review (§5c) flagged both the host-escape
  risk (the eval runtime can bypass a wrapper namespace via
  `(js/require "child_process")`) and per-call latency.
- **mvm** (`reference-code/mvm`) is a substrate refinement of option 1, not a
  third topology: the deep-dive maps inspect's 4-method
  `SandboxEnvironment` ABC onto mvm's SDK (`@sandboxenv("mvm")`, effort M),
  and the microvm experiment holds the owner-resolved threat model
  ("per-agent isolation needed NOW, to contain mistakes"). Adopt mvm's
  isolation layer only — never its agent/tool framework.

**Recommendation (recorded twice in the spike, §5c + §5d): option 1,
pod-in-sandbox** — with ONE capability-fn library whose CONTRACTS are stable
(`seon.agent.shell`/`fs`/`http` + `my.*` wrappers) so case-1 (pod-on-host,
fns hit the DB) and case-2 (pod-in-container, fns act locally) share one
corpus and differ only in the adapter/boot. "Design the fn contracts now;
defer the case-2 container work until a case-1 benchmark lands."

**Decision status: OPEN.** The spike explicitly flags it as an "open design
question for the owner (genuinely undecided — needs a decision before case-2
build)". What IS settled by the owner: functions ARE the tool surface (no
tool-calling retrofit; "write functions for all tools", bridge adapts) —
§5d. The docker-vs-mvm choice within option 1 is also open (docker is the
inspect default and cheaper to start; mvm is the owned isolation direction).

## Recommended build order

1. **`seon.agent.shell` + `my.shell`** — the only tool with a fully closed
   design; unblocks python and most GAIA-level-1 actions; ~1 day. Register
   the `:seon.shell/*` schemas exactly as written in the research note
   (with the `ok?`-=-RAN refinement, superseding the `toolkit.md` sketch).
2. **`my.py/run`** — half a day on top of shell (stdin-mode `python -`);
   decide interpreter selection at build time. Inspect's python tasks need
   it; nothing else does yet.
3. **`seon.blob` + `my.blob`** — independent of 1–2, can run in parallel;
   ~1–2 days. It is wanted NOW for always-on turn/prompt capture (the
   observability design) and it gives web-fetch its oversized-body landing
   zone. Decide hash (SHA-256), compression (verify Node zstd, else gzip),
   and the size threshold at kickoff; ship without GC, documented.
4. **web-fetch (`seon.agent.http` + wrapper)** — needs a short design pass
   FIRST (gate name, response caps, HTML→text, blob composition) since no
   written design exists; then ~1 day build. Do it after blob so the "big
   body → blob + projection" move is available on day one.
5. **`edit-file`** — owner-gated; half a day once sanctioned. Only needed
   when an inspect task family actually scores file edits — don't build
   speculatively against the toolkit's no-file-editing thesis.
6. **Browser** — DEFER. Out of lane per the benchmarks survey; revisit only
   if a chosen benchmark family strictly requires DOM interaction that
   fetch + shell cannot cover.

Rationale: order = (design-readiness × benchmark-unblocking). Shell is both
the readiest design and the widest unblocker; python is a free rider on it;
blob is ready-enough and independently wanted for capture; web needs the one
missing design pass; editor and browser are gated on owner decisions that
should not stall the first four. The substrate decision (case-2) does NOT
block any of these — the fn contracts are substrate-stable by design; it
blocks only the container/boot work, which follows a case-1 baseline.

## Source index (everything found)

- `docs/prds/agent-fsm/toolkit.md` — §`my.shell`, §`my.files`, §`my.search`,
  §`my.code`, §`my.blob` (no §`my.py`, no web, no editor).
- `docs/prds/agent-fsm/research/tool-my-shell-research.md` — the shell design.
- `docs/prds/agent-fsm/archive/tool-design-decisions.md` — consolidated
  eight-tool decisions, backbone shapes, build order, `my.blob` deferral.
- `docs/prds/agent-fsm/research/tool-my-files-research.md` /
  `tool-my-search-research.md` / `tool-my-code-research.md` — the built
  floors' designs (house template provenance).
- `docs/prds/agent-fsm/research/inspect-seon-bridge-spike-2026-07-01.md` —
  §5b–§5e: fns-as-tools, substrate options, `my.py/run`, http/fetch need.
- `docs/prds/agent-fsm/research/inspect-ai-harness-deep-dive-2026-06-30.md` —
  sandbox ABC, `bash()`/`python()`/`web_browser()` built-ins, mvm provider.
- `docs/prds/agent-fsm/research/microvm-isolation-experiment.md` — mvm scope
  + the owner-resolved threat model.
- `docs/prds/agent-fsm/research/agentic-benchmarks-survey-2026-06-26.md` —
  which benchmark families need web/shell/python; GUI out of lane.
- `docs/prds/agent-fsm/research/balanced-benchmark-battery-2026-07-02.md` —
  the current four-axis battery (needs NO new tools; case-2 families do).
- `docs/prds/agent-fsm/research/core-tools-status-2026-06-28.md` — built-vs-
  spec status + the concise-output laws (grouped grep, honest hints).
- `docs/prds/agent-fsm/research/result-paging-design-2026-06-27.md` — blob
  considered for oversized eval results (context for blob's scope).
- `docs/seon/architecture/observability.md` — the fuller `my.blob` target +
  always-on turn capture (`:seon.agent.turn/prompt-blob`).
- `docs/prds/embeddings/batched-cache-archive-design-2026-06-25.md` — the
  content-addressed-archive precedent (SHA-256, append-only).
- `src/seon/agent/fs.cljs`, `src/seon/agent/fs/internal.cljs`,
  `src/seon/agent/search.cljs`, `src/seon/agent/search/internal.cljs` — the
  house template in code.
