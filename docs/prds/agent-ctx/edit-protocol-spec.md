---
type: prd
status: active
tags: [prd, agent]
---

# Edit-protocol build spec — remaining units (T2, A4, A5) + Arc B image

Executable specs for opus seon-agents. Orchestrator manages; each unit is
one agent. Master plan: `~/.claude/plans/make-a-plan-to-staged-trinket.md`
(approved 2026-07-05). Research:
`docs/prds/agent-ctx/research/cross-language-indexing-2026-07-05.md`.

## State (update as units land)

- DONE (orchestrator): `src/seon/code.cljc` — `:seon.code/lang`/`::text`/
  `::block` schemas + `block?`/`text` helpers; boot-required in
  `client.cljs`.
- DONE 2026-07-06 (verifier pass pending): Unit A2+A3 — `replace!` /
  `insert!` / `view` in `seon.agent.fs` (+ `::file-sha` on `read-file`),
  pure cascade in `src/seon/agent/fs/match.cljc` (`decide` is the entry
  point; `number-lines` the one formatter; JVM-runnable — proven via
  `clojure -M:test` standalone). Suite 1033/4753/0/0, 0 core faults,
  live-proven incl. ambiguous refusal with candidates. **VERIFIED**
  (adversarial pass all green; zero wrong-place mutations across probes:
  overlapping, no-op, multi-byte, near-straddle, CRLF, empty content).
  NOTE: A2's files were swept into peer commit `24d5c49c` (docs-titled —
  the shared-index trap; work is in and green, left as-is). Coverage-gap
  fix agent dispatched: pin overlapping/no-op/multi-byte + `::near`
  START-line-only semantics in `match_test.cljc`. T2 is UNBLOCKED — read
  `match.cljc` for the final API.
- DONE 2026-07-06 **VERIFIED** (5/5: byte-fidelity/spans live-probed,
  eval-source dual representation, failure modes incl. trailing-space
  sentinel, parse-forms contract regression confirmed fixed on the live
  build, render no-double-escape proven against the message-block path):
  Unit A1 — `#code/<lang>
  <<SENTINEL` heredoc literal. Pre-pass in `seon.repl.internal` splices a
  machine-escaped map literal; segment map rebases `:seon.repl/source`/
  `::span` onto ORIGINAL text; heredoc form entries additionally carry
  `:seon.repl/eval-source` (cljs-readable rewrite — the self-host eval
  path re-reads source strings and cannot read raw heredocs). Parinfer
  delimiter-repair is REFUSED on spans holding a heredoc opener.
  `parse-forms` public contract unchanged (`parse-forms*` is the private
  token loop). Render: block values → lang-tagged fenced code via the one
  walker. Suite 1045/4789/0/0, 0 core faults; JVM parser tests 35/312/0.
  Staged, not committed. `/repl` skill updated.
- FOLLOW-UP (owner call, surfaced by A1): the eval-log datom's `::source`
  for a heredoc form records the machine-spliced eval-source, not the
  heredoc as typed (the entry's `:seon.repl/source` does keep the raw
  heredoc, so nothing is lost). If transcripts should DISPLAY the heredoc
  as typed, `eval-form-entry!` must thread display-source vs eval-source
  separately. Small unit if wanted.
- OWNER DECISIONS PENDING (surfaced by A2, recommendations recorded):
  (1) retire `edit-file` in favor of `replace!`/`insert!` after the A/B —
  one edit primitive, not two; (2) add `[seon.agent.fs :as fs]` to
  `config/system.edn` `:seon.eval/home-requires` (fs is boot-required but
  NOT in the agent toolbelt — agents can't discover the new verbs; folded
  into A5 step 0 below pending owner ack); (3) `::content` widened
  `:string` → `:seon.code/value` (deliberate, backward-compatible).
- FOLLOW-UP (pre-existing, out of A2's scope): `read-file`/`write-file`/
  `edit-file`/`walk-dir` use destructuring `:or {encoding "utf-8"}` which
  doesn't fire on explicit nil (node returns a Buffer) — sweep to
  `(or encoding "utf-8")` like the new verbs. Small unit, anytime.
- Shared-branch rules for every agent: `feature/agent-ctx` is shared with
  the slice-4 bench agent. Stage with explicit pathspecs, never commit,
  never touch `evals/`, `docker/`, `src-inspect-ai/` unless the unit says
  so. No default-cluster resets.

## Unit T2 — gold-patch replay harness (falsification test for A2)

**Blocked by:** A2 (needs `seon.agent.fs.match`'s final API — read it, don't
assume this spec's key names survived implementation).

**Deliverable:** `bin/replay-gold-patches` (executable script; bb or node —
pick whichever loads `match.cljc` with least ceremony; it is `.cljc`, no IO,
malli-using) + a results file
`evals/runs/<date>-t2-gold-replay/README.md` with the metrics table.

**Method:**

1. Instance list: `evals/runs/2026-07-05-slice4-dev-pass/dev-ids.txt`.
   For each instance, obtain the repo checkout at the instance's base
   commit and its gold patch. Source: the `princeton-nlp/SWE-bench_Verified`
   HF dataset row (`repo`, `base_commit`, `patch`) — the slice-2/3 evidence
   dirs and `evals/datasets.lock` show how prior units pinned data; reuse
   that path. Clones go under `tmp/` (never `/tmp`).
2. For each gold-patch hunk: derive a find/replace pair — `find` = the
   hunk's context+deleted lines exactly as in the pre-image; `replace` =
   context+added lines. Multi-hunk files: apply hunks top-to-bottom,
   recomputing content between applications.
3. Drive the PURE cascade only (no fs verbs, no pod): content string +
   find/replace(+ optional near window from the hunk header) → decision.
4. Score per hunk: `exact` (stage 2 hit), `near-rescue` (stage 3 hit),
   `norm-rescue` (stage 4), `refused` (fail decision), `WRONG` (applied but
   resulting content ≠ post-image region). Post-image oracle: `git apply`
   the gold patch separately and compare the affected regions.
5. Second pass — ambiguity honesty: rerun each find WITHOUT its near
   window; count how often the cascade correctly refuses on multi-match
   instead of picking one.

**Acceptance:** WRONG = 0 (hard gate — a single wrong-place mutation fails
the unit); report the full metrics table (n hunks, exact %, rescue %,
refusal %) in the README + a row appended to
`docs/prds/agent-ctx/coordination.md`'s slice log. If WRONG > 0, do NOT
tune the harness to pass — report the failing case verbatim; the cascade
gets fixed, not the test.

## Unit A4 — parsed pytest results (optional for the A/B; build after T2)

**Deliverable:** pytest-output parsing that turns test failures into data +
a derived context section. Two consumption points, ONE parser.

1. **Parser** — pure fn(s), new ns `src/seon/agent/testrun.cljs` (name
   final unless a better existing home is found — check the ONE-mechanism
   table first; do NOT put it in shell). Input: stdout+stderr strings.
   Output: `{::ok? true, ::framework :pytest, ::passed <int>,
   ::failed <int>, ::errors <int>, ::failures [{::test-name <string>, ::path <string>,
   ::line <int, optional — absent when unparseable>, ::message <string>}
   …]}` — all keys namespaced to the ns, registered schemas. Parse the
   `short test summary info` section + `FAILED path::test - message`
   lines; tolerate `-q`/`-v`/`--tb=short` variants. Unrecognized output →
   `{::ok? false, ::framework :unknown, :seon.error/message
   "unrecognized test output format"}` (errors-are-values; never a
   throw, never a guess); recognized output → `::ok? true` on the map.
2. **Shell integration** — in `seon.agent.shell/run`'s response assembly:
   when the argv PREFIX is pytest-shaped (`["pytest" …]`, or
   `["python" "-m" "pytest" …]` / `["python3" "-m" "pytest" …]` — note
   argv[0] alone is `"python"` in the -m form, so match the prefix, and
   as a computed rule not a hand-list if more variants appear) AND the parser
   recognizes the output, attach the parsed value under a namespaced key in
   the run-response (extend `::run-response` schema in place). Raw
   stdout/stderr behavior unchanged (still token-capped).
3. **Derived section** — a section fn (reactive-context pattern; read
   `docs/seon/concepts/reactive-context.md` + how existing sections
   register via `:seon.agent.ctx/block`): renders the CURRENT latest
   parsed failure set for the agent (from the persisted projection of the
   last pytest run — store the parsed map as datoms/blob per the
   three-tier size rule), renders nothing when the last run was green.
   No stored "seen" flags.

**Acceptance:** unit tests with captured pytest output fixtures (green run,
failures, collection error, `-q`); a live-pod proof: run pytest via the
shell verb in a scratch dir, show the envelope's parsed key and the section
rendering, then a green run and the section vanishing. Full `bin/test-cljs`
once.

## Unit A6 — tool parity sweep (owner-ordered 2026-07-06; before A4)

Bring the investigation/edit surface to parity with the Claude Code
toolkit. Five items, all extending existing nses IN PLACE:

1. **`seon.agent.search/grep` `::context-lines`** (optional int, 0–10ish
   cap): in `full?` mode include N context lines around each hit (rg
   `-C`); in by-file mode widen the preview similarly. Line numbers on
   every emitted line; token caps + honest truncation as today.
2. **`grep` `::multiline?`** (optional boolean): rg `-U
   --multiline-dotall` so patterns can span lines (multi-line signatures,
   decorators). Document the cost in the docstring.
3. **`seon.agent.fs/replace!` `::all?`** (optional boolean, default
   false): replace every occurrence without knowing the count —
   mutually exclusive with `::expected-count` (schema-enforce). Cascade
   semantics: `::all?` legitimizes any count ≥1 at the matched stage;
   candidates/refusal behavior otherwise unchanged. Extend
   `match.cljc` purely; tests for interleaved/adjacent occurrences.
4. **`walk-dir` parity check**: recursive glob filter, mtime sort option,
   bounded results with honest totals. Add only what's missing, in place.
5. **`seon.agent.shell` background jobs** (owner-ratified 2026-07-06):
   `run-bg!` → `{::ok? true, ::job-id …}`; job table in the globalThis
   VOLATILE tier only (no datoms, no tmp tee — buffer dies with the pod,
   honest because the child process does too); `job-status`
   (running/exited + exit code + runtime); `job-output` full-so-far +
   optional `::since-offset`, each call an ORDINARY eval value riding
   `result/<id>` (no verb caps; ~2MB/stream ceiling, honest truncation);
   `job-stop!` SIGTERM; exited buffers pruned oldest at a cap; PLUS a
   DERIVED context section (reactive-context pattern) rendering
   running/recently-exited jobs with the job-output idiom inline,
   rendering nothing when the table is empty. No wake-trigger
   integration in this unit. Canonical envelopes; same grants as `run`. Confirm `run` has no hard
   timeout ceiling below several minutes (bench pytest runs).

6. **No destructive clipping at verb boundaries (owner design ruling
   2026-07-06 — REPLACES the earlier per-verb-cap audit):** display
   economy belongs to the render layer (the `render/value` sampler) +
   transcript decay, NOT to verbs. The invariant: a verb may return a
   capped PREVIEW, but the full output must be recoverable and the
   envelope must name its recovery handle. The render-bounding map
   found exactly one destructive site: `seon.agent.shell` out/err
   (token-capped at the verb, process buffer drops earlier bytes, full
   text gone). Deliverables (AMENDED by owner 2026-07-06 — shell output
   is an ordinary eval value, NOT web-page-shaped; blobs would be a
   second mechanism): (a) run/py-run return FULL `::out`/`::err`
   strings, NO verb token caps (delete the 2048/16384 preview cuts;
   keep honest `::out-tokens`/`::err-tokens`); sole bound = process
   byte ceiling ~2MB (RAM guard) with honest `::truncated?` + dropped
   bytes. Display clipping belongs to the existing eval-value path:
   `result/<id>` stash (full) + render sampler skeleton + decay.
   Durability = the agent's explicit `my.blob/put!` on the stashed
   value (docstring says so). NO `::out-blob`/`::err-blob`. Background
   jobs keep the full stream in the globalThis job table; `job-output`
   pages are ordinary eval results. Live proof required: a huge `:out`
   renders as bounded skeleton with `⟨N tokens⟩` head + the
   `result/<id>` handle, and the handle resolves to the full string.
   (b) fix the stale grep docstring (claims default 20 rows; runtime is
   12 — `search/internal.cljs:44`); (c) sweep: every clipping verb's
   envelope names its recovery handle; report per-verb.

Acceptance: schema-specced + instrumented, error envelopes, docstrings
≤72-char first lines, tests per item (hermetic), one full `bin/test-cljs`,
live proof of each verb incl. a background pytest run polled to
completion. Update toolkit.md.

## State addendum — residuals + suite (2026-07-06)

A1-residuals unit DONE: eval-source docstrings restated to the true
trigger (rewrite ≠ original, pinned by test); the preflight_repair red
was a TIMING RACE (repair-budget-ms 50 expires on cold JVMs →
`budget?` falls into the plain did-you-mean branch) — test relaxed to
behavior. Suite FULLY GREEN: 1050/4809/0/0, no core faults. OWNER ITEM
(recommended fix, queued post-A/B): the `budget?` fall-through message
in seon.eval (~3680-3702) claims "none compile-proven" when the budget
merely expired — give it an honest "trial budget exceeded — candidates
not verified" note. STAGING NOTE: internal.cljc/internal_test.cljc
staged hunks include the whole heredoc unit (uncommitted) — commit
them as the A1 unit, not separately.

## State addendum — T2 RESULT (2026-07-06)

T2 COMPLETE, **hard gate PASSES: WRONG = 0** across 15 gold hunks / 10
instances; 100% stage-1 exact, git-apply oracle 0 mismatches; direct
ambiguity probe 8/8 correct refusals, 0 guesses. Caveat recorded: gold
hunks are clean, so rescue stages (near/norm) went unexercised by real
anchors — rescue coverage lives in `match_test.cljc`, not this harness.
Evidence: `evals/runs/2026-07-06-t2-gold-replay/`. Harness:
`bin/replay-gold-patches` + `bin/replay_gold_patches.clj`.

## State addendum — A7 audit RESULTS (2026-07-06)

Audit COMPLETE (verbatim samples: research/rendered-output-audit-2026-07-06.md;
aging proven end-to-end on a real aged transcript row). Findings + rulings:
FIX NOW (agent dispatched): sampler map-elision keeps smallest values
first (computed rule — handles/hashes survive, payloads elide);
my.blob/text refuses binary media; drill line gains the
`keep: (my.blob/put! result/<id>)` idiom; eval-row clip labels convert
chars→tokens (hard rule). DEFERRED: pruned-handle honesty → the
result-persistence unit (byte-stability collision; graceful-miss today);
decay 16384/1500 near-indistinguishable (sampler pre-bounds) → eval-lane
schedule A/B question. FLAG to tooling lane: pod crashed 3× on peer
hot-reloads mid-async-continuation during the audit (reading 'call' /
IDeref null at reload) — registry-worthy stability signal, not this arc.
Tier eviction is INERT by default (::tiers [] — dead path until a
manifest wires it). Audit leftovers in default store: disposable agent
hlh-2607061447 + run + blobs (no reset performed).

## Post-handoff unit (owner call): compute the bootstrap schema

A4 flagged `seon.client/agent-bootstrap-attrs` (client.cljs ~419) as a
HAND-MAINTAINED keyword list that must be manually synced with every
`schema/register!` — a standing-directive violation. Recommended unit:
derive the install schema from the `seon.schema` registry (all
DB-storable registered attrs) so registration is the single act.
Follow-up also recorded: background pytest runs surface their parse on
`job-status` (derived) but are NOT projected into the `:test-failures`
section — clean persistence needs a close-handler seam; revisit if the
bench drives want bg runs in the section.

## Post-handoff unit (tooling lane): quiesced hot reload (drain-then-swap)

Fixes the class-2 instability (reload swap under live async
continuations; 4+ crash datapoints 2026-07-06). Mechanism: the pod is
single-threaded, so reloads interleave with SUSPENDED continuations at
`await` points — a resumed continuation touches a half-swapped world
(`reading 'call'` on a mid-redef ns; `IDeref null` on old instances vs
re-defined protocol tables). Fix via shadow-cljs lifecycle hooks in
`seon.client`:

1. `^:dev/before-load-async` — LATCH: admit no new turns/evals (flag at
   the loop-fold boundary), then BOUNDED DRAIN: await in-flight
   evals/turns settling (~5s; evals are short, long work is background
   PROCESSES which hold no CLJS continuations). Resolve only when quiet
   → shadow applies the whole batch atomically w.r.t. continuations.
2. `^:dev/after-load` — existing re-instrumentation + release latch.
3. Drain timeout → DEFER the reload to the next turn boundary (never
   force-swap); still blocked → escalate to a clean supervised pod
   restart (pod is disposable, DB is truth, agents resume).

Residual hygiene (review-level, not machinery): durable state as DB
data not closures; `defonce` atoms hold data not fns. Layered strategy:
frozen bundles for runs that must not move (bench/T4 — exists),
quiesced reload for interactive dev, `:crash` dial unchanged.

## Post-handoff unit (owner-ratified 2026-07-06): result persistence

True restores for `result/<id>` across pod restarts. Design settled, do
NOT build before the T4 handoff: (1) free win first — small values whose
full pr-str already sits in `:seon.eval/result-edn` (≤ verbatim cap)
rehydrate on first re-reference via read-string; (2) bigger values: at
stash time attempt serialization through a BOUNDED ABORTABLE printer
(counting writer, ~256KB budget, aborts mid-walk — contains the CPU/RAM
spike, infinite-lazy hang, and per-result disk cost); success → EDN text
blob (content-addressed, human-readable) + hash on the eval datom; lazy
rehydrate on re-reference, never eager at boot; (3) abort/non-EDN →
volatile as today, and resumed rows render an EXPLICIT missing marker
("result/3 — expired: pod restart; re-run"). Disk-growth policy
follow-up: consider persisting only results that survive to decay
offset 2. A7's expiry-honesty findings calibrate the marker.

## Unit A8 — value-representation consolidation + fabrication context fix

Both research docs (fabrication-complete-gate + value-representation-
consolidation, 2026-07-06) converge: agent-facing worked examples teach
the double `;;=>` result-echo, which IS the fabrication shape and is
DISTINCT from the runtime's real single-`;=>` render. Fix = context.

UPGRADED 2026-07-06 (owner) — RESERVED-GLYPH design supersedes plain C+
(strictly more robust + a fabrication deterrent; hairiness ~3): marker
= `⟹` (U+27F9). Runtime marks real results `; ⟹ value ; result/<id>`
at the ONE emit site (`format-eval-row`; stays inside the `;` comment —
a bare `⟹` breaks eval'able-context). Neutralizer retargets: ANY `⟹` in
agent NARRATION → `[unverified narration]` (reserved codepoint drops the
fragile `;`-count + column-0 `:=>`-collision anchoring; keep legacy
`;;=>`/bare-`=>` as belt-and-suspenders). Examples standardize to
`; ⟹ value`. System-text carries CRYSTAL-CLEAR agent language: `⟹` is a
REAL result the runtime writes on the NEXT turn; typing it yourself does
NOTHING (replaced with `[unverified narration]`); never `complete` on an
unseen result. CLEANUP (owner): consolidate the whole runtime-only glyph
vocabulary (`⟹` result · `«…»` shape · `⟨N tokens⟩` size · `‹partial
view›` · `result/<id>` handle) into ONE authoritative docs/conventions.md
section ("the runtime writes these; agents never author them"), with
section comments at the emit site + neutralizer cross-linking it — so the
disparate sources become known, named sections (also resolves the a4df
"two markers for one concept" artifact). Warn-only lint flags non-`; ⟹`
echoes. Update tests asserting the old `;=>` prefix; one-time transcript
reflow accepted. Impl in flight (af08). The plain-C+ steps below are the
pre-upgrade record:

1. Standardize EVERY agent-facing (`.cljs` + `seon-skills/`) worked
   example to the runtime's single `;=>` format + the `«…»` shape-marker
   convention (db.cljs is the model; fix the 5 capability tools
   fs/shell/web/search/testrun + mixed my.*; the `.clj` PAUSED track is
   out of scope). This also satisfies the comment-level rule (inline
   result echo = single `;` prose; double `;;` is block-comment-above-
   form ONLY).
2. Add ONE always-on system-text line (match its prose density): you
   write FORMS, never their results; the runtime shows the REAL result
   (`;=>`) next turn; a `;;=>` you typed is fiction; never `complete`
   on a result you have not seen rendered.
3. Add a WARN-ONLY `seon.dev` lint flagging double `;;=>` in agent-
   facing docstrings (computed rule, prevents regression).
DEFERRED: Option A (generate examples from the renderer, hairiness 4) —
follow-up. The derived complete-gate on a RED testrun (defense-in-depth)
— build ONLY if a re-drive shows fabrication persists after A8.
OWNER-FLAGGED artifacts (from a4df): two elision glyphs for one concept
(`⟨tokens⟩`/`‹partial›`/`«shape»` — use what the runtime already emits,
don't add a 3rd); ~30 static example strings vs one renderer (= Option A).

## Unit A9 — concentrate REPL-usage instruction (after A8; audit-backed)

Audit: research/repl-usage-instruction-audit-2026-07-06.md. Finding: the
single home ALREADY effectively exists — `seon.agent.ctx/system-text`
(the live system-message `def`, ~60% REPL mechanics; edits reach ALL
agents next turn, unlike seed-copied blocks). Smoking-gun drift (fixed by
A8): `result-marker` "⟹" (ctx.cljs:616) claims single-source, but
format-eval-row emits a literal `; ⟹` AND system-text teaches the old
`;=>` — 3 unsynchronized copies. A9 (hairiness 2, does NOT block the
handoff per the audit): EXTEND system-text into `;;;`-labeled subsections
(one authoritative REPL-usage home; interpolate the constant, never
literals) covering the loop mechanics + the glyph vocabulary; TRIM the
/repl skill's 3 topics that duplicate system-text down to pointers (skill
stays the deep-dive); CLOSE the gap the audit found — no always-on
`^:async`/`await` rule today (only the non-default /clojurescript skill).
Line: shared loop mechanics + glyph vocabulary concentrate; a verb's own
CALL example stays in its docstring. Do NOT add a new block (seed-copy
reaches only future agents). Owner-driven anti-fragility.

A9 ALSO BUNDLES the instruction-surface migration (audit:
research/instruction-surface-migration-2026-07-06.md; owner directive:
de-emphasize invoke-only skills as a home for load-bearing rules). Skill
mechanism: an always-on L0 catalog line per skill + an L2 body rendered
only when `(my.skills/load :name)`d; default load-set `[:repl]`, so 5 of
6 skill BODIES are opt-in — the trap. MIGRATE these 4 load-bearing rules
into the always-on system-text floor (~150 tok, ~2.5% of ~6000):
(1) `^:async`/`await` + Promise auto-await (bare top-level await throws —
same line as the REPL concentration); (2) every map key namespaced
(general); (3) public-fn `:malli/schema` is instrumented + throws;
(4) entities = attributes + connections, no `:type`/`:kind`. STAYS as
skill depth (pointer-reachable): datahike query/CAS/as-of, data-modeling
design, DoC rationale, ui-live-tiles helpers, clojurescript gotchas, repl
parinfer/`:read` taxonomy. Each skill losing a floor rule gets a one-line
back-pointer ("the core rule is always in your context; this is the deep
dive"). DRIFT FIXES (keep-current): the `my.skills`/`list-skill-files`
docstrings falsely claim `.claude/skills` symlinks the corpus — the
rendered corpus is `seon-skills/` (6 skills); fix them. Fix system-text's
false self-description. Collapse the vestigial empty shared-instructions
block. ANTI-DRIFT: a warn-only dev lint (reuse the existing linter)
flagging a load-bearing rule that lives ONLY in a skill body. Hairiness 2;
does NOT block the handoff. A9 docs pass ALSO posts the T4 drive RESULTS
entry to coordination.md (verdict + D1/O5/O1 fix shas + fabrication →
A8) — the driver captured evidence under evals/runs/2026-07-06-t4-tool-drive/
but (correctly) did not touch the PRD log; the coordinator/A9 propagates it.
CLEANUP: the stale t4drive cluster (warm pod) is destroyed+recreated fresh
as part of the confirming re-drive prep (post A8+A9).

## State addendum — T4 DRIVE RESULT (2026-07-06)

24 scored drives + observer audit. **Gate verdict: the TOOLS PASSED;
the "fail" is model honesty.** Evidence: evals/runs/2026-07-06-t4-tool-drive/.

- TOOLS SOLID: zero wrong-place mutations in 25 samples (anchored-edit
  safety held live); canary proved a real DeepSeek agent DISCOVERS +
  uses grep/context-lines, view+sha, replace!, bg jobs from context
  alone; legacy edit-file 0/24 (retirement usage-safe); rendering
  A7-honest across all 25 (observer); decay byte-stable + usable stubs
  confirmed on a 20-turn drive.
- FIXED (committed): D1 pod crash e0c730b3 — an agent eval returning a
  realization-throwing lazy seq ((keys non-map)) crashed the pod as a
  MISCLASSIFIED :core fault because the throw landed in render-result-edn
  OUTSIDE the eval guard. NOT tool-specific — a latent gap in the
  never-throw-into-the-loop invariant (render was not total); render/value
  is now total against poison. O1 view-content-elided-to-stub e0c730b3
  (dominant-string body). O5 web/search honest empty hint 8906782b.
- FABRICATION — ROOT-CAUSED (a54e, research/fabrication-complete-gate-2026-07-06):
  our own docstrings teach `(call {…}) ;;=> {result-map}` dozens of times;
  the T4 fabrications structurally MATCH it. Clincher: the runtime already
  has `seon.agent.ctx/neutralize-result-claims` (`result-claim-re`) that
  flags that exact `;;=>` shape as `[unverified narration]` — we teach the
  pattern our own runtime treats as a lie. Real runtime result render is a
  DISTINCT `=> value ; result/<id>` shape (the mismatch). FIX = CONTEXT not
  mechanism (lever 0): make examples match the runtime `=>` format + one
  system-text line "you write FORMS, never their results." This IS the
  value-representation consolidation (a4df, task #10) — land as one unit.
  The derived complete-gate (refuse on latest testrun RED) is DEMOTED to
  optional defense-in-depth, ship-and-measure only if fabrication persists.
  SEPARABILITY VERDICT: the tool handoff can proceed now (tools honest,
  zero wrong-place mutations, fabrication can't inflate the oracle score);
  plan is to land the cheap context fix + confirming re-drive FIRST since
  it directly helps the bench agent's real runs.
- TEACHING GAPS (minor, re-drive prep): ::since next-cursor idiom not
  SHOWN in the docstring (uniform ::since 0); candidates flow 0/24 —
  partly probe design (the poker contract's expected-count-up-front
  clause dodges it), partly compact-card omission. insert! 0/24 (model
  preference, not a defect).

## Design rule — which tier holds a verb's big output (settled 2026-07-06)

**Locally reproducible → value tier** (`result/<id>` stash; render sampler
+ decay do all display clipping; no verb caps): shell out/err, grep
results, fs reads — anything a re-run recreates. **Remote / mutable /
expensive-to-recreate → blob tier at the boundary** (content-addressed,
citable from datoms via `:my.blob/hash`, restart-surviving): web-fetch
pages, turn prompts/replies, media. Agents PROMOTE value→blob explicitly
(`my.blob/put!`) when an ephemeral value earns durability. Blobs are the
durable-content tier, not a binary tier — most blob content is text.

## Unit A7 — rendered-output audit across decay levels (owner-ordered 2026-07-06)

Read what the agent ACTUALLY SEES (flag-garbage rule), for every verb,
at every transcript age. Method: on the live pod, produce one real eval
per verb/value-shape (grep, grep-graph, fs view/read/list/replace!/
insert!, shell run incl. an over-cap output, my.blob put/text,
web-fetch, my.plan, plus raw eval values: small <1500 verbatim, medium,
huge >16384, wide map, deep nest, long string, lazy seq, opaque JS/db
value). Then age them through the decay schedule (offsets 0 / 2 / 5 —
drive enough subsequent turns; use the ctx/transcript render fns or
`seon.agent.inspect/turn` byte-exact replays) and CAPTURE the rendered
text verbatim at each level. Judge each sample: shape-preserving? honest
markers? recovery handle present and usable (result/<id> resolves, blob
hash pages, from-line re-view works)? any garbage (invalid EDN, escaped
blobs, mid-token cuts, useless previews)? any verb returning crap
(noise fields, redundant data)? Deliverable:
`docs/prds/agent-ctx/research/rendered-output-audit-<date>.md` with
verbatim samples per verb × decay level + a findings table + fix list.
Sequencing: audit stable surfaces first; shell/grep/web-search LAST
(A6 + web-search units are changing them in flight — re-check git/log
before auditing those). Constraints: no cluster reset, no cljs.test in
the live pod, dedicated agent id (not root), tmp/ scratch only.

## Unit A5 — bench wiring, docs, A/B handoff (after A1+A2 land + verify)

0. **Toolbelt exposure (pending owner ack — see State):** add
   `[seon.agent.fs :as fs]` to `config/system.edn`
   `:seon.eval/home-requires` so the anchored-edit verbs render as a
   compact card agents can discover; verify the bench entrypoint's env
   grants fs write on `/testbed` (`SEON_FS_ROOT`/`SEON_FS_READ_ONLY` —
   the pre-slice-4 debt unit set these). Without this step the A/B tests
   nothing.
1. **Image/overlay refresh:** rebuild the canonical Seon bundle so bench
   containers get A1+A2 (the `/opt/seon` overlay volume is built at a
   pinned digest — see `evals/runs/2026-07-05-slice1-canonical-image/` +
   `docker/`). COORDINATE FIRST with the slice-4 agent via
   `docs/prds/agent-ctx/coordination.md` (they own the entrypoint/read-only
   blocker fix; do not race their image work — add a coordination.md entry
   and wait for ack if their work is mid-flight).
2. **Docs:** `docs/seon/architecture/toolkit.md` (new fs verbs + `#code`
   literal), `docs/prds/agent-ctx/roadmap.md` (we-are-here),
   `coordination.md` (close the P0 in-place-editor ask, ~line 1522, with
   the shas). The `/repl` skill entry for `#code` should have landed with
   A1 — verify, add if missed. PLUS truth-in-labeling (A6 verifier
   finding, pre-existing): `walk-dir`'s `::total-found` is the count
   found BEFORE the walk stopped at the cap, not a true grand total
   (unknowable without an unbounded second pass) — docstring + hint must
   say so; do NOT add a counting pass.
3. **T4 live drive — THE HANDOFF GATE (owner-ordered 2026-07-06: the
   bench agent is waiting on this; garbage in, garbage out).** DeepSeek
   drives (pre-authorized) on scratch NON-CLOJURE repos — Python first,
   then one more language (small TS repo) — with tasks that force EVERY
   tool through real use: grep (incl. context-lines) → view → `#code`
   heredoc → replace! (incl. one deliberately ambiguous edit to observe
   the candidates flow live) → insert!; an over-preview pytest run via
   background jobs (poll to completion, page output from blob); blob
   put/text; web search → fetch → blob compose. A DEDICATED observer
   agent reads the rendered transcript each turn (the agent-facing
   text, not envelopes) and flags garbage; orchestrator reviews.
   Defects → fix agent → re-drive. ONLY a clean drive unlocks step 4.
   Prereq: step 0 toolbelt exposure (owner ack'd 2026-07-06) + context
   refresh so the verbs render discoverable. STABILITY (owner discussion
   2026-07-06): run the T4 drives against a FROZEN bundle (the eval
   lane's out-bench mechanism — presence-only creates, sha-asserted) so
   peer hot-reloads cannot swap code under a live drive. The underlying
   class-2 instability — reload swap under live async continuations
   (4+ crash datapoints today: 'reading call', 'IDeref null',
   'jobs_block') — is REGISTRY work for the tooling lane; recommended
   fix: drain-then-swap (watcher signals, pod finishes in-flight
   turns/evals, then applies the build atomically). The `:crash` dial
   itself is working as designed (it caught a half-committed
   config/ns pair at boot) — do not soften it.
4. **A/B handoff (owner framing 2026-07-06 — PRELIMINARY tools,
   feedback requested):** post in coordination.md to the eval lane:
   frozen dev slice rerun, before/after = existing tools vs
   +heredoc+anchored-edit+parity tools; metrics = resolved count +
   edit-failure incidents from the ledger. Frame the tool surface as v1
   under active refinement and explicitly request structured feedback
   from the docker-isolated runs: which verb agents reached for
   (replace! vs the legacy edit-file — retirement decision pending),
   where they flailed or retried, rendered-context defects WITH
   captured evidence (the standing attribution contract). Their
   real-repo runs are the second feedback loop after T4. The A/B result
   gates Arc B's build (owner decision 2026-07-05).

## Arc B pre-work — indexer image packaging (may run anytime; code gated)

Spec for a standalone unit touching ONLY `docker/Dockerfile` (+ a smoke
script), coordinated with slice-4 as in A5.1: bake pinned versions of
`scip` CLI, `scip-python`, `scip-typescript` (npm), `scip-go` + Go
toolchain, `rust-analyzer`, `scip-java`, `scip-clang` + `bear`,
`tree-sitter` CLI + grammars (py/ts/js/go/rust/java/c/cpp). T6 smoke: with
networking off, index a tiny fixture repo per language inside the image;
`scip print --json` must yield non-empty symbols with signatures; record
results in an `evals/runs/` evidence dir. Language servers are NOT in this
unit. The ETL/attrs/verbs specs for Arc B proper live in the research file
+ plan §Arc B — do not build them until the T5 gate opens.

## Verification protocol (orchestrator runs after each unit)

Spawn a `seon-verifier` (cheap) per landed unit: check the diff against
this spec + the plan, re-run the unit's targeted tests, confirm the live
proof claims against the pod, check docstring/namespaced-key/envelope
conventions, and answer: "how would this be broken and did the tests check
that?" Only then mark the task completed and stage files.
