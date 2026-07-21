# WP-A — sci fork error patch + structural classification (`seon.error.sci`)

Working dir /Users/sean/src/seon, branch codex/runtime-reliability-refactor.
SHARED tree: touch ONLY reference-code/sci (a `seon` branch commit on the
fork), NEW src/seon/error/sci.clj, src/seon/host.clj,
src/seon/host/context.clj, and new/updated tests under the writer gate
(test/seon/host_*_writer_test.clj family). Path-limited commit only. Never
edit any CLAUDE.md.

ROLE: principal engineer. Better seam or spec gap → STOP and report with
evidence. Stopping early is FREE — your session resumes with full context,
so never push through a doubtful seam to avoid "wasting" the run.

DESIGN AUTHORITY: docs/prds/sci-execution-runtime/research/
error-quality-u6-w3-design-2026-07-21.md — read it COMPLETELY first. This
spec implements its §2 (error taxonomy, classes 2.3-2.6 + 2.8), §4 (the sci
patch), and the WP-A row of §6. §2.1/2.2 (instrumentation classes) are WP-B;
§2.7/§3 (preflight) are WP-C; §2.9 + render work are WP-D — build `classify`
so those classes slot in WITHOUT reshaping.

GROUNDING (confirm each; ruling 10 — read the real source, report better
seams and the upstream's own names):
1. reference-code/sci: `src/sci/impl/resolve.cljc:322-332` failure arm +
   local `throw-error-with-location` wrapper (:11-12);
   `utils.cljc:62-70` (data merge), :47-56 (interrupt marker);
   `interrupt.cljc:32-42` (`interrupt!` data arg); `core.cljc:402-410` +
   `utils.cljc:296-305` (`sci/stacktrace`, `f-meta` frames). Remotes:
   `origin` = babashka/sci, `fork` = seantempesta/sci, HEAD `be4021d`.
2. src/seon/host.clj:407-435 (`built-in-var-refusal?`, `eval-error-value` —
   the landed W0.2 steering you fold in), :543 (the interrupt message regex
   you kill).
3. src/seon/host/context.clj:965-968 (`:interrupt-fn`), :921-923 (the
   resolve regex in `load-portable-slice!` you kill).
4. src/seon/repair/candidates.cljc (`rank-candidates` — already a host
   wrapper, context.clj:631-636) for `:resolution` suggestions.
5. src/seon/error/instrument.cljc — the existing malli envelope/classifier
   vocabulary `classify` must pass through untouched.
6. seon.ai.tokens (`estimate`/`clip-str`) — the ONE token budget mechanism
   for `steering-head`.

GOAL — every host error classifies STRUCTURALLY (zero message regex) into
one `:seon/error` value with an abridged steering head:
1. sci fork patch (~2 lines + one test, committed on a `seon` branch of the
   fork submodule): widen resolve.cljc's local `throw-error-with-location`
   to take data; the failure arm passes
   `{:phase "analysis" :sci.impl/symbol sym}`. Pure-additive; message
   untouched (upstream-PR framing is design §4 — keep the diff that clean).
2. NEW `src/seon/error/sci.clj` owning the `:seon.error.sci/*` keys
   (key-namespace ruling) with three public fns per design §2:
   `classify` (Throwable → classified error value; walks the cause chain
   once; consumes sci/malli/ArityException's own terms and translates at
   this one boundary), `steering-head` (error value + token budget →
   abridged head string), `detail` (Throwable → full addressable detail
   map — built now, retained by WP-D). Register the
   `:seon.error.sci/class` enum exactly as design §2 lists it.
3. Classes wired now: `:resolution` (patch ex-data + ranked
   `:seon.repair/suggestions`), `:arity` (structural ArityException +
   `:arglists` meta), `:interrupt` (identical? on the interrupt marker;
   `:interrupt-fn` passes `{:seon.error/kind :timeout}` data;
   cancel-vs-timeout control state stays in `run-invocation!`), `:refusal`
   (fold W0.2's `built-in-var-refusal?`/`eval-error-value` in; drop the
   message-regex fallback arm only if the hostile suite proves the
   structural `:var` path complete — otherwise keep it and report),
   `:runtime` (residual; top-3 `sci/stacktrace` frames via `f-meta`, design
   §2.8). Malli envelopes (`instrument-error?`) pass through with class
   keys added — no reshaping.
4. Replace the host call sites: host.clj error paths call
   `classify`/`steering-head`; context.clj's interrupt-fn carries the data
   map; load-portable-slice!'s resolve regex is deleted in favor of the
   structural ex-data.
5. Head text budget: cap via `seon.ai.tokens/clip-str`; bind the default to
   ONE named var documented for the W1 config-fact relocation
   (`:seon.config.render/error-head-token-cap` is the proposed W1 name;
   default 120 tokens). Heads follow the design's worked examples: cause,
   suggestion, place, in the agent's own terms; full-detail references
   (`result/<id>`) land in WP-D — do not fake them now.

TESTS (writer-gate JVM ns, follow host_*_writer_test.clj idiom; behavior,
never exact strings — assert `:seon.error.sci/class` + structural keys,
never prose): one hostile classification suite producing each wired class
LIVE (unresolved symbol, wrong arity, deadline interrupt, built-in var
write, plain runtime throw), asserting class, structural keys
(`:seon.error.sci/symbol`, suggestions presence, frames shape), and that
the interrupt/resolution paths carry NO message parsing:
`rg -n 're-(find|matches)' src/seon/host.clj src/seon/host/context.clj
src/seon/error/sci.clj` clean of error-classification regexes (report any
remaining regex with justification).

GATE: sci fork tests for the patch, focused new suite green, then full
bin/test-writer green (report honest counts; STOP on any pre-existing
failure).

COMMIT: submodule commit on the fork's `seon` branch first, then one
path-limited repo commit
  git commit --only -m "Classify host errors structurally through seon.error.sci" \
    -- reference-code/sci src/seon/error/sci.clj src/seon/host.clj src/seon/host/context.clj <the test paths you added>

SUMMARY: grounding confirmations, seam findings, whether the refusal regex
fallback could be dropped, chosen named defaults + W1 notes, gate counts,
unresolved items.
