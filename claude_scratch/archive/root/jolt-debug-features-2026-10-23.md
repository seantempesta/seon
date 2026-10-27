# Jolt debug features vs. our REPL path (read-only research, 2026-10-23)

Pin: reference-code/jolt at 4342b73c (submodule HEAD; docs/forks.md:16 says f05de7db is the
branch tip, so HEAD may differ: unverified which is built; both descend from upstream 0.8.23+Unreleased).
bin/build:64,87-97 builds the Jolt copied from reference-code/jolt, so source there = what runs.
NOT verified by running anything (read-only brief): every "reaches agents" claim below is from
reading code. A one-line REPL eval of `(prinltn 1)` through seon_eval would settle items 1-2.

## What an agent sees today
- Path: seon.server :eval -> seon.repl/eval-string (src/seon/repl.clj:341) -> eval-code (:56).
  A throw is caught at repl.clj:93, `[:err e ns (outer-frames)]`. Reply item gets :err-text
  (nrepl/err->msg, repl.clj:105) and, via wrap-caught (repl.clj:134-147), :err = agent/error-map
  and :printed = (render/ai em {}).
- agent/error-map (src/seon/agent.clj:227-243): :seon.agent/eval-error with :seon.error/message =
  ex-message, :class, and :data = (print/text (ex-data e) part-budget) i.e. ex-data as a printed
  STRING, not data. Task seon.agent/an-eval-error-carries-its-data (resources/seon/tasks/seon.agent.edn:33)
  already owes "data travels as data".
- seon_eval text = seon.render.ai/items (src/seon/render/ai.clj:190-198): "x " + :printed, which is
  render.ai/error (ai.clj:74-94): message, fix, then "(type in operation {own keys})".
  :err-text is in item-keys (repl.clj:293) but items does not print it.
- Compile error is NOT data with :line/:column at the top level: they are inside the quoted :data string.

## 1. Did you mean
a. Exists: jolt-core/jolt/analyzer.clj:1962-2033 (edit-distance, suggestions-for, resolve-error);
   README.md:422-428; CHANGELOG.md:14480-14486 (0.4.10). In our pin.
b. Reaches agents: YES by accident, in the message. resolve-error builds msg with "(did you mean a, b, c?)"
   (analyzer.clj:2025-2028) and ex-info message = that; error-map takes ex-message, so :seon.error/message
   and render show it. nrepl/err->msg (jolt-core/jolt/nrepl.clj:329-336) deliberately strips the suffix
   from :err-text (CHANGELOG.md:339-342), but our items do not print :err-text. Also :jolt.error/suggestions
   is in ex-data -> appears inside the :data string (budget-cut). Unverified live.
c. Cost: none. Nothing to build. Risk: only the pending ex-data-as-data task changes its shape.

## 2. JOLT_DIAG=edn
a. Exists: host/chez/cli-core.ss:66-68 (jolt-diag-machine? reads getenv), :132ff jolt-report-uncaught,
   jolt-diagnostic-map :96-130. Keys are :jolt.error/{message,line,column,file,type,kind,symbol,suggestions,ns,token,source}
   (cli-core.ss:36-45; analyzer.clj:2029-2031) - namespaced, not the README's bare :message.
b. Reaches agents: NO. It only runs in the CLI top-level uncaught handler (jolt-report-uncaught).
   Our eval catches Throwable (repl.clj:93) so it never fires there; it could only affect a dying
   agent/root's stderr (stderr-to-file!, agent.clj:517; tail recorded on death, failure-points.md:42,61-63).
   The same data is ALREADY in-process: the CHANGELOG (14490-14492) says the analyzer attaches it as ex-data
   regardless of the env var. So the right use is (ex-data e), not the env var.
   Env-var law: the var is read by Jolt (cli-core.ss:66), not src; AGENTS.md §5 bans System/getenv in
   our src outside the loader (src/seon/config.clj:39,50,305 are the only uses; check.clj:222 lists it as hidden input).
   Setting JOLT_DIAG would be in bin/env or bin/root (shell), not src: no letter-of-law conflict, but it is
   an undocumented knob and buys nothing. Recommendation: do not set.

## 3. JOLT_CHECK
a. Exists: host/chez/compile-eval.ss:450-492 (jolt-check-lint? = getenv at load time of that Scheme
   file; jolt-lint-node! called at :560 in jolt-analyze-emit-form*), implemented by
   jolt-core/jolt/passes/types.clj:1366 check-form (+ report-dead-hints! :818). CHANGELOG.md:14493-14500.
b. Reaches agents: NO even if set. Warnings are written to Scheme (current-error-port) (compile-eval.ss:488,
   496-503), i.e. fd 2 = the agent's stderr file, read only on death. Not into item :err/:out (those bind
   Clojure *err*/*out*, repl.clj:90). Whether the getenv is evaluated at process start or baked in a built
   image: not verified (it is a toplevel `define`; I believe boot-time).
c. Env-only as a switch (a Scheme global; no Clojure setter). BUT the checker is a public fn and our
   REPL already gets every analyzed node: wrap-record binds jolt.analyzer/*unit-tap* to (program/tap acc)
   (repl.clj:197, program.clj:214-230), whose tap is called with [form node] (compile-eval.ss:561-562, analyzer.clj:131) right
   after the lint hook. So `(jolt.passes.types/check-form (jolt.passes.types/new-unit) node)`
   (types.clj:63,1366; returns [{:op :argpos :type :msg :pos}]) per form is possible with NO env var, as data,
   and the item already has a :warnings key (item-keys, repl.clj:293; used by wrap-instrument repl.clj:258).
   Fit with rules: it RECORDS findings (data), gates nothing: consistent with "get out of an agent's way".
   Not a contract/guard. Risks: check-form is non-strict (fewer false positives) but unmeasured on our code;
   it re-walks each node (cost per form unmeasured, needs a cost proof per AGENTS §3/§5); new-unit state
   (user-sig-box) is per-unit, so use one fresh unit per form or one per agent (strict? false needs none).
   Does not fire for AOT-cached code (only runtime-compiled), which is exactly REPL forms.

## 4. JOLT_DEBUG
a. Exists, several sites: host/chez/loader.ss:1020 (aot-info "[jolt.aot]" cache miss reasons),
   jolt-core/jolt/deps.clj:140 (fetch/cache lines), host/chez/java/class-extensions.ss:233 (registry drift),
   stdlib/jolt/socket.clj:627, main.clj:1364. CHANGELOG 5460-5518.
b. Reaches agents: NO (all stderr). Useful to the builder (why an AOT cache missed; docs/source.md shared
   caches), not to an eval. If wanted, belongs in `bin/build`/`bin/check` diagnostics, one env in a shell script,
   debugging only. Not recommended for standing use (noise, and bin/env keys caches on behavior; unverified that
   JOLT_DEBUG leaves AOT key unchanged).

## 5. nREPL
a. Exists: jolt-core/jolt/nrepl.clj (start, err->msg :293, outer-frames), README.md:455-493, CHANGELOG 1194 (:port-file).
   Middleware/cider are from external jolt-lang/nrepl, which is NOT in our deps.edn (grep: no match) -> cider
   info/complete/error-analysis are NOT in our pin.
b. We already use jolt.nrepl as a LIBRARY: repl.clj:30 requires it, uses nrepl/err->msg (:82,105,357) and
   @#'jolt.nrepl/outer-frames (:33). docs/processes.md:21 and vocabulary.md:34-38 say the REPL speaks nREPL's
   MESSAGE MODEL. The server (docs/server.md) speaks EDN lines and MCP JSON-RPC only; no bencode, no
   .nrepl-port anywhere in src/bin/docs/resources/test (grep).
d. jolt.nrepl/start would be a second request path to eval: bypasses seon.server ops, :origin :outside recording
   on the branch (server.md :eval), per-form ticks (repl.clj:61), wrap-print/caught/record middleware, contracts.
   Also a second listener port (ports are per checkout via bin/ports). That is AGENTS §4 "built once" violated.
   Adds only editor interop (Calva/CIDER for the owner); not for agents. Reject for agents; owner-editor interop
   would be a separate, owner-asked task (then as an adapter over seon.repl/handler, not over jolt.nrepl's eval).

## 6. clj-kondo
a. Exists: reference-code/jolt/clj-kondo.exports/jolt-lang/jolt/{config.edn,hooks}; README.md:495-520; CHANGELOG.md:2244-2257.
   It only teaches kondo `jolt.ffi` macros (defcfn etc.).
e. Ours: no .clj-kondo dir, no mention in bin/, src/, docs/, AGENTS.md, deps.edn (grep). clj-kondo exists on this
   machine (/opt/homebrew/bin/clj-kondo) but is not a check. defcfn appears in 1 file of ours (src/seon/lib.clj, 2 lines).
   Our code is dominated by seon.contract/defn and defchecked (which would need lint-as/hooks too): kondo would
   flood with unresolved-symbol noise before it found anything, and the compiler already reports unresolved symbols
   with suggestions at load, and `bin/check contracts`/mg-check cover arity/contract. Not worth it; at best an
   owner-editor convenience. Do not add to checks.

## Ranked tasks worth filing
1. seon.agent/an-eval-error-carries-its-data (EXISTS) - extend its plan: keep ex-data as data, so a compile error's
   :jolt.error/{line,column,symbol,suggestions,kind} arrive as keys; render prints "did you mean" once, with line:col.
   Write set: src/seon/agent.clj (error-map), src/seon/render/ai.clj (error), seon.error schema if :data typed.
   Proof: existing seon.agent-test/an-eval-errors-data-is-printed-once + new deftest evaluating `(prinltn 1)` asserting
   :suggestions contains "println" and :line 1. Deletes: print/text of ex-data in error-map, the double print.
2. Type findings per eval form as :warnings from check-form over the node the tap already holds.
   Write set: src/seon/repl.clj (wrap-record or a small wrap-check), program.clj tap keeps node; resources/seon/tasks/seon.repl.edn.
   Proofs: red->green deftest `(+ 1 :a)` yields a :warnings item; cost proof (work done, calls) per form; false-positive
   sweep over our own src loaded through the REPL (must be zero warnings, or each named). Deletes: nothing now; replaces
   the idea of setting JOLT_CHECK. Gate: owner go (new item content = new format question per Root's gate #4).
3. (docs only) Wiki note in docs/errors.md/docs/processes.md that JOLT_DIAG/JOLT_CHECK/JOLT_DEBUG/nREPL server/kondo are
   deliberately unused, with the reasons above, so no agent re-proposes them. Write set: docs only; no proofs; deletes nothing.

## Could not verify
- Live agent output for `(prinltn 1)` (no server/binary run); whether :data is cut before the suggestions at part-budget.
- Whether :jolt.error/line is present for a form read by our LineNumberingPushbackReader (should come from reader meta).
- Whether the built target/ binary's jolt-check-lint? is evaluated at boot or build time.
- Which commit is actually built (4342b73c HEAD vs. f05de7db in docs/forks.md).
- Cost of check-form per form and its false-positive rate on our code.
