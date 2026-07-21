---
type: research
status: active
tags: [research]
---

# Edit-hook parsing + paren auto-fix vs clojure-mcp

## TL;DR

**Partial — and the auto-fix half is effectively DEAD.**

We DO use a modern parser (edamame) on the edit-hook PreToolUse path, and we
DO have a real parinfer-based delimiter-repair capability — but they are wired
to different paths and the repair never reaches an edit:

- **PreToolUse (bb, in-process, ALWAYS runs):** edamame validates syntax and
  **BLOCKS** on unbalanced delimiters. It does NOT attempt any repair. An
  agent's missing/extra paren is REJECTED, not fixed, with a "balance your
  delimiters" message.
- **PostToolUse repair (`seon.dev.repair/repair-and-format`, parinfer +
  cljfmt):** the only auto-fix step. It runs **inside the JVM, invoked over
  nREPL on :7888** — which is the **PAUSED track**. With the JVM down (`:7888`
  is currently CLOSED), `process-via-nrepl!` returns `nil` and the repair stage
  never executes. So today the edit hook is **block-only; auto-fix is a no-op.**

**The single biggest gap:** the structural repair we already built and proved
live (`seon.repair`, cljc, parinferish indent-mode — the eval pipeline's
self-correction) is **runtime-independent and reusable in the bb PreToolUse
path**, but it isn't wired there. We block edits we could auto-balance.
clojure-mcp does exactly this auto-balance (parinfer indent-mode, then
re-validate with edamame) and treats it as a first-class repair tool.

We are NOT behind on technique — same library family (parinfer), same
re-validate-after-repair discipline, same edamame delimiter detection. We're
behind on *wiring*: our best repair lives on the eval path and the pod, not on
the edit hook.

---

## Three-column comparison

| Dimension | OUR edit-hook (`bin/seon-hook` + `seon.dev.repair`) | OUR eval parser (`seon.repair` / `seon.eval`, ALREADY done) | clojure-mcp (`paren_repair` + `delimiter`) |
|---|---|---|---|
| Where it runs | PreToolUse: bb in-process. PostToolUse repair: JVM via nREPL :7888 | CLJS pod (live), also cljc for JVM tests | JVM MCP server, nREPL-connected |
| Parse/validate lib | **edamame** (`parse-string-all`, permissive opts) | **rewrite-clj** parser (per-form, error-isolating) + edamame-equivalent read gate | **edamame** (`delimiter-error?` checks `:edamame/opened-delimiter`) |
| Repair lib | **parinferish 0.8.0** (`:mode :indent`) + cljfmt | **parinferish 0.8.0** (`:mode :indent`), NO cljfmt | **parinfer** (`com.oakmac.parinfer` Java, `Parinfer/indentMode`) + cljfmt |
| On a paren error | **PreToolUse BLOCKS** (edamame invalid → `:decision "block"`). PostToolUse *would* parinfer-repair+cljfmt+rewrite — but only if JVM up | Records a `:read` failure entry, then **layers a best-effort parinfer indent repair ON TOP**, re-reads to confirm, auto-evals the repaired form, surfaces the diff to the agent | **Auto-balances** via parinfer indent-mode, re-validates with edamame, writes file, returns a unified diff |
| Auto-fix vs block | **Block (PreToolUse). Auto-fix dead (JVM paused).** | **Auto-fix** (accepted only if changed AND re-reads clean) | **Auto-fix** (accepted only if `.success` AND no residual delimiter-error) |
| Accept-guard | none (block is terminal) | "changed AND re-reads cleanly" via injected `reads?` gate; surfaces `:seon.repair/changes` | "`success` AND not `delimiter-error?` after repair"; returns diff |
| Status | live but block-only | **live + proven** (`ari-2606180804` episode) | reference design |

---

## The gap, precisely

1. **Our auto-repair is dead on the edit path.** `bin/seon-hook -main` flow:
   PreToolUse Clojure edits go through `validate-clojure-edit` →
   `validate-clojure-syntax` (edamame) → on imbalance returns
   `{:decision "block" ...}` and outputs it. There is **no repair call in the
   bb script at all**. The repair (`stage-repair` →
   `seon.dev.repair/repair-and-format`) lives in `src/seon/dev/hook.clj`, which
   the bb script reaches only via `process-via-nrepl!` → `nrepl-eval 7888`.
   **`:7888` is CLOSED (JVM track paused), so that returns `nil`** and repair
   never runs. Net for an agent editing a `.clj/.cljs/.cljc`: a paren error is
   **rejected at PreToolUse**, full stop. (Even if the JVM were up, repair is
   PostToolUse — it would fire *after* a write, but PreToolUse already blocked
   the imbalanced edit, so the two stages never compose on the bad case.)

2. **The reusable capability already exists and is runtime-independent.**
   `seon.repair` (cljc, `src/seon/repair.cljc`) is pure: parinferish
   indent-mode parse → flatten → diff, gated by an injected `reads?`
   predicate. It deliberately drops the JVM-only cljfmt and the edamame probe
   (the caller already knows it failed to read). It is the eval pipeline's
   self-correction and is **live-proven on the pod** (`ari-2606180804`). It
   depends only on `parinferish` + `seon.schema` — **no JVM, no datahike, no
   nREPL**. parinferish 0.8.0 is already a project dep (`deps.edn:52`).

   **Conclusion:** the bb PreToolUse path could call a parinfer indent repair
   directly (bb can load `parinferish` as a dep, or shell to the cljc logic),
   re-validate with the edamame check it already runs, and **auto-fix the edit
   instead of blocking** — matching clojure-mcp's behavior, reusing our own
   proven repair semantics. No JVM revival required.

---

## Recommendation (ranked)

**1. (Do this) Make the bb PreToolUse path auto-balance before blocking.**
When `validate-clojure-syntax` reports an imbalance on the reconstructed
content, attempt a parinfer indent-mode repair in-process, re-run the edamame
check, and:
- if it now reads clean AND the change is a pure delimiter fix → **rewrite the
  edit's content** (write the repaired file / adjust `new_string`) and allow,
  surfacing the diff to the agent as feedback (clojure-mcp returns a unified
  diff — do the same so a wrong-but-valid repair stays visible);
- if it still doesn't read → keep today's BLOCK with the balance hint.

Mechanics: add `parinferish` to `bb.edn` deps (it's pure Clojure, bb-loadable)
and port the ~10-line core of `seon.repair/repair-source` (parse `:mode
:indent` → `flatten` → diff) into the bb script, OR keep one cljc source of
truth and require it from bb. This is **~half a day** and removes the
single biggest gap: agents stop getting rejected for fixable hiccups.
Accept-guard must mirror what we already use: *changed AND re-reads cleanly*
(do NOT accept a repair that silently restructures — `seon.repair`'s docstring
documents exactly the indent-mode failure modes).

**2. (Cheap consistency win) Retire or revive the JVM PostToolUse repair.**
`seon.dev.repair` + `stage-repair` in `hook.clj` is dead while :7888 is down
and duplicates `seon.repair` minus the cljfmt step. Per "don't be a dumbass /
no two code paths": once #1 lands, the bb path owns delimiter repair. Either
(a) delete `seon.dev.repair` and have `hook.clj` reuse `seon.repair` for the
JVM track if it ever returns, or (b) leave a one-line note that it's
JVM-track-only and currently inert. Do NOT keep two parinfer wrappers.

**3. (Optional, later) cljfmt-on-edit.** clojure-mcp formats with cljfmt after
repair (configurable `:cljfmt true/:partial/false` — matches our
`.claude/seon-hook.edn :repair {:cljfmt true}`). cljfmt is JVM-only (not bb),
so this stays gated on the JVM track. Low priority — repair is the value,
formatting is cosmetic, and the pod doesn't need it.

**Do NOT adopt clojure-mcp's parinfer Java lib (`com.oakmac.parinfer`).** We
already have `parinferish` (pure Clojure, bb + cljs + clj), which is strictly
better for our dual-track reality — the Java parinfer would not load in bb or
the CLJS pod. Same technique, better library choice; keep ours.

**Honest worth-it call given dual-track reality:** YES for #1 — the edit hook
runs on **both** tracks (it's the bb gate every agent edit passes through,
JVM up or down), and #1 is pure bb + a dep we already own. #2 is cleanup. #3
is JVM-only polish, defer.

---

## Key clojure-mcp snippets (verbatim, so we don't re-read the repo)

Delimiter detection — edamame, checks for `:edamame/opened-delimiter`
(`reference-code/clojure-mcp/src/clojure_mcp/delimiter.clj`):

```clojure
(defn delimiter-error? [s]
  (try
    (e/parse-string-all s {:all true
                           :read-cond second
                           :readers (fn [_tag] (fn [data] data))
                           :auto-resolve name})
    false ; No error = no delimiter error
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        (and (= :edamame/error (:type data))
             (contains? data :edamame/opened-delimiter))))
    (catch Exception _e
      true)))  ; conservatively allow repair attempt
```

Repair core — parinfer indent-mode, accepted only if success AND no residual
error (`reference-code/clojure-mcp/src/clojure_mcp/sexp/paren_utils.clj`):

```clojure
(defn parinfer-repair [code-str]
  (let [res (Parinfer/indentMode code-str nil nil nil false)]
    (when (and (.success res)
               (not (delimiter/delimiter-error? (.text res))))
      (.text res))))
```

Pipeline — repair THEN cljfmt, write, return unified diff
(`reference-code/clojure-mcp/src/clojure_mcp/tools/paren_repair/core.clj`,
abridged):

```clojure
(let [has-delimiter-error? (delimiter/delimiter-error? original-content)
      repaired-content (if has-delimiter-error?
                         (paren-utils/parinfer-repair original-content)
                         original-content)
      _ (when (and has-delimiter-error? (nil? repaired-content))
          (throw (ex-info "Could not repair delimiter errors" {...})))
      final-content (if cljfmt-enabled?
                      (form-edit-core/format-source-string
                        (or repaired-content original-content) opts)
                      (or repaired-content original-content))]
  ;; write final-content, return {:delimiter-fixed ... :formatted ... :diff ...}
  )
```

Tool surface — exposed to the LLM as a callable `paren_repair` tool
("Fix delimiter errors … using parinfer … Returns a status message and diff").
Annotated `:destructive? true :idempotent? true`. (clojure-mcp deps:
`org.clojars.oakes/parinfer 0.4.0`, `rewrite-clj 1.1.47`, `cljfmt 0.13.1`,
`clj-kondo 2024.03.13`.)

Note: clojure-mcp's clj-kondo usage is for its lint/eval feedback channel
(quality warnings), **not** for delimiter repair — paren repair is
parinfer-only. So there's no kondo-driven auto-fix to chase; our edamame +
parinferish stack already covers the repair technique they use.

---

## Files referenced

- `/Users/sean/src/seon/bin/seon-hook` — bb PreToolUse edamame block path
  (`validate-clojure-syntax`, `validate-clojure-edit`, `-main`); no repair call.
- `/Users/sean/src/seon/.claude/seon-hook.edn` — `:repair {:cljfmt true
  :revert-on-fail true}` (drives the JVM-only PostToolUse stage).
- `/Users/sean/src/seon/src/seon/dev/hook.clj` — `stage-repair` →
  `seon.dev.repair/repair-and-format` (runs only over nREPL :7888 = paused).
- `/Users/sean/src/seon/src/seon/dev/repair.clj` — JVM repair (parinferish +
  cljfmt + edamame probe). Dead while JVM down; duplicates `seon.repair`.
- `/Users/sean/src/seon/src/seon/repair.cljc` — **the reusable repair**
  (parinferish indent-mode, injected `reads?` gate, no JVM deps). Wire THIS
  into bb.
- `/Users/sean/src/seon/src/seon/repl/internal.cljc` — eval parser
  (rewrite-clj, per-form error isolation, fence-strip, `prose-token?`); notes
  it does NOT auto-fix parens — the eval pipeline layers `seon.repair` on top.
- `/Users/sean/src/seon/src/seon/eval.cljs` — calls `seon.repair`; surfaces
  the A.2 repair note + diff to the agent.
- clojure-mcp: `reference-code/clojure-mcp/src/clojure_mcp/delimiter.clj`,
  `.../sexp/paren_utils.clj`, `.../tools/paren_repair/{core,tool}.clj`.
