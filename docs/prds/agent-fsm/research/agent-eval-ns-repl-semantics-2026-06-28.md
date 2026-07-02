---
type: research
status: active
tags: [research, agent]
---

# Agent eval-ns setup — real REPL semantics vs the brittle magic

Owner-directed research (2026-06-28). Deliverable: diagnosis + clean
rebuild design for how a live agent's home namespace acquires its verb
and data refers. NO src edits in this pass — `eval.cljs` is Core's lane.

## TL;DR

The agent home-ns setup (`seon.eval/setup-agent-ns!`) is brittle on TWO
independent axes, and both are now LIVE-PROVEN to break:

1. **Persistence axis (the thing that just broke).** The refer/alias
   wiring is a one-time imperative side effect written into a `defonce`
   compile-state that is **version-stamped to `seon.eval/init-version`**.
   Editing `eval.cljs` (the dd411373 Promise-ergonomics commit did exactly
   this) rotates `init-version`; the next `seon.repl/ensure-bootstrap!`
   **rebuilds a fresh compile-state via `init-bootstrap!`**. `init-bootstrap!`
   does NOT re-establish agent home-ns wiring, and nothing replays
   `setup-agent-ns!` after a rebuild — only `boot-one-agent!` runs it, at
   agent boot. So between any hot-reload of `eval.cljs` and the next agent
   (re)boot, **every live agent's home ns has no refers** → `message/user`,
   `wait`, `complete` are undefined. The commit "broke `setup-agent-ns!`"
   not by editing it (it is textually unchanged) but by invalidating the
   compile-state its wiring lived in.

2. **Self-host-refer axis (the reason setup is full of hacks).** You
   cannot cleanly `:refer` host-bundled vars in self-host. `seon.agent.lifecycle`
   / `seon.agent.message` are shadow-compiled into the pod bundle, so the
   self-host analyzer has **no `:defs` entry** for `wait`/`complete`/`user`.
   `cljs.analyzer/check-uses` requires every refer'd var to exist in the
   analyzer's `:defs` table and **THROWS** `:cljs/analysis-error` when it
   doesn't (it is NOT a benign warning — the current docstring is wrong on
   this point). The three hacks in `setup-agent-ns!` (the bare-`(ns)`
   goog-provide prime, the "don't trust `:ok`" tolerance, the `(fn? complete)`
   probe) are all downstream symptoms of this one unaddressed constraint.

**Both axes collapse to a single clean fix, LIVE-PROVEN below:** declare the
toolkit nses' public surface to the analyzer's `:defs` **once, inside
`init-bootstrap!`** (the same synthetic-`:defs` mechanism `bind-result-var!`
already uses for `result/<id>`). Then the agent home-ns `(ns home (:require
[… :refer …]))` is **normal, clean Clojure ns semantics**: `:ok true`, no
warning, the emit completes and provides the runtime object — so the
goog-prime and the probe both become unnecessary, AND every compile-state
rebuild re-establishes toolkit visibility, so a future auto-await-style edit
can never silently un-wire the verbs.

## The immediate blocker (fix-or-revert NOW, independent of the rebuild)

LIVE-PROVEN on the running pod (2026-06-28, pod booted 16:23):

```clojure
(let [cs   @seon.repl/!compile-state
      home (seon.agent.ctx/home-ns "root")]
  (get-in @cs [:cljs.analyzer/namespaces home]))
;; => nil   ;; my.agent.root has NO analyzer ns entry at all
```

So in every live `root` turn, `(message/user …)` / `(wait …)` / `(complete …)`
resolve against a non-existent ns → undefined-var. Fully-qualified
`seon.db/…` still works (those are real loaded nses). Re-running
`(seon.eval/setup-agent-ns! @seon.repl/!compile-state 'my.agent.root "root")`
immediately restores `:uses`/`:requires` — so setup itself is not broken; the
wiring was DROPPED by the post-edit compile-state rebuild.

Stop-gap until the rebuild lands (pick one):
- **Cheapest:** `bin/seon cluster reset default` re-boots every agent →
  `boot-one-agent!` re-runs `setup-agent-ns!` against the fresh
  compile-state. Restores verbs immediately; does not fix recurrence.
- **Slightly better stop-gap:** have the per-turn entry (or `eval-batch!`'s
  fold seed) call `setup-agent-ns!` idempotently when
  `(get-in @cs [::namespaces home])` is nil, so a rebuild self-heals on the
  next turn. This is also the seed of the durable design (§Design).

The durable rebuild (below) makes the stop-gap unnecessary.

## Ground truth — why each hack exists (cljs.js / cljs.analyzer source)

Grounded in `reference-code/clojurescript/src/main/clojure/cljs/analyzer.cljc`.

### Hack 1 — the `:undeclared-var`/`:ok false` tolerance

The current docstring claims the host-bundled `:refer` "produces a benign
`:undeclared-var` warning that flips `:ok` to false." **It is not a warning —
it is a thrown analysis error.** LIVE:

```text
REFER FORM => :ok false
  error: "Invalid :refer, var seon.agent.lifecycle/complete does not exist"
         {:tag :cljs/analysis-error}
PROBE (fn? complete) in my.probe.x => :ok true   ;; uses already merged
```

Mechanism, exact:

- `cljs.analyzer/parse-ns` merges `:uses`/`:requires` into
  `[::namespaces lib …]` **before** validation runs. That is why the probe
  resolves even though the form "failed."
- `check-uses` (analyzer.cljc:2933) iterates the uses and `(throw (error …
  :undeclared-ns-form))` on any `missing-use?`.
- `missing-use?` (analyzer.cljc:2881) is, at heart:
  `(= (get-in cenv [::namespaces lib :defs sym] ::not-found) ::not-found)`
  — i.e. "the refer is missing iff the analyzer has no `:defs` entry for it,"
  modulo JS/goog/node escape hatches.
- The throw propagates as `Could not parse ns form` →
  `{:tag :cljs/analysis-error}` → `:ok false`.

So the `:ok false` is genuine and unavoidable **as long as the toolkit nses
have empty analyzer `:defs`**. The tolerance hack is treating the symptom.

### Hack 2 — the `(fn? complete)` probe

Pure consequence of Hack 1: because the refer form reports `:ok false` even
though `parse-ns` already wired the uses, setup cannot trust `:ok` and instead
asserts success by resolving a refer'd var. Remove the throw and the probe has
nothing to compensate for.

### Hack 3 — the bare-`(ns home)` goog-provide prime

The docstring's root-cause is correct for the OLD world: a `(defn foo)` in
`my.agent.X` emits `my.agent.X.foo = …`, which assumes the nested object path
exists; `goog.provide` is forced unreliable in self-host (`goog.isProvided_`
→ false), so the object only gets created when an **ns-form emit COMPLETES**.
The host-bundled `:refer` **threw during analysis, aborting the emit before
the object was provided** — so the require/refer form wired the analyzer entry
but left no runtime object, and the agent's first `(defn …)` wrote into
`undefined`. The bare `(ns home)` prime (no refer → nothing to throw on)
provided the object as a workaround.

**This prime is only needed because the refer form throws.** Kill the throw
(Hack 1) and the refer form's own emit completes and provides the object —
proven below. The prime is then dead code.

## How real self-host REPLs do this cleanly

Bootstrapped CLJS REPLs (planck, lumo, replumb/Klipse) refer `cljs.core` and
their bundled namespaces with **no goog-prime, no probe, no warning-tolerance**
— because they ship **AOT analysis caches** for those namespaces and load them
into the compiler env's `::namespaces` at startup (planck/lumo embed
`cljs/core.cljs.cache.json`; replumb's `*load-fn*` returns `{:lang :js …
:cache <edn>}` for known nses). That cache is exactly a populated `:defs`
table. With `:defs` present, `missing-use?` returns false, `check-uses` does
not throw, the ns form analyzes cleanly, and the refer "just works" — ordinary
ns semantics.

JVM Clojure is the same idea by construction: `clojure.core` vars are interned
in a real `Namespace`, so `refer-clojure` / `(ns user (:require …))` resolve
against live var objects. `user` refers `clojure.core` by default for the same
reason.

The seon constraint: the toolkit nses (`seon.agent.lifecycle`,
`seon.agent.message`, `seon.agent`, `seon.schema`, `seon.db`, `seon.agent.todo`)
are **host-bundled (shadow), not analyzed from source in the self-host env**, so
**no analysis cache / `:defs` ships** for them. Seon already solved the identical
problem for `result/<id>` (`bind-result-var!` writes a synthetic `:defs` entry
so a bare `result/<id>` resolves with no undeclared-var warning). The clean fix
is to do the same, deliberately, for the toolkit's public surface — a hand-built
analysis cache for exactly the host-bundled nses agents refer.

This is NOT fighting the compiler. Declaring `:defs` for a namespace whose
implementation lives elsewhere (host JS) is precisely what an analysis cache
IS. We are giving self-host the same `::namespaces` seed that planck/lumo ship
for their bundled libs.

## The fix — LIVE-PROVEN

Synthetically declare the toolkit vars' `:defs`, then run a **normal** ns
refer form and a defn into the home ns:

```clojure
;; declare host-bundled toolkit surface to the analyzer (once)
(decl 'seon.agent.lifecycle '[wait complete pause resume terminate])
(decl 'seon.agent.message  '[user agent system])

;; normal ns refer — NO prime, NO probe
(eval cs "(ns my.probe.y (:require [seon.agent.lifecycle :refer [wait complete]]
                                    [seon.agent.message :as message]))" …)
;; => :ok true   (clean — no error, no warning)

(eval cs "(defn greet [] (complete))" {:ns 'my.probe.y})
;; => :ok true   (the clean ns emit PROVIDED the runtime object;
;;                the defn wrote into a real object, not undefined)
```

where `decl` is the existing synthetic-`:defs` shape:

```clojure
(swap! cs (fn [s]
  (-> s
      (assoc-in [:cljs.analyzer/namespaces ns-sym :name] ns-sym)
      (assoc-in [:cljs.analyzer/namespaces ns-sym :defs sym]
                {:name (symbol (str ns-sym) (str sym))}))))
```

Both hacks (prime + probe) are confirmed unnecessary once `:defs` exist.

## Design — clean, robust agent-eval-ns setup (Core lane)

Goal: agents get a stable, predictable refer/alias set via **standard ns/REPL
semantics**, robust to any future eval-flow change. Three pieces:

### 1. A toolkit-visibility seed that is part of `init-bootstrap!`

Add a single `seon.eval/declare-toolkit-ns-defs!` step to `init-bootstrap!`
(`seon.eval`, alongside the rest of bootstrap) that writes synthetic `:defs`
for the host-bundled toolkit surface agents refer:

- `seon.agent.lifecycle` → `wait complete pause resume terminate`
- `seon.agent.message` → `user agent system` (the verbs the context teaches)
- `seon.agent`, `seon.schema`, `seon.db`, `seon.agent.todo` → these are
  alias-only (`:as`), not `:refer`, so they do not need `:defs` for
  `check-uses`; but seeding their `:name` is cheap and keeps the analyzer
  consistent. (Refer surfaces are what MUST be declared; aliases resolve
  without a `:defs` table.)

Because this runs inside `init-bootstrap!`, **every** compile-state rebuild
(version bump / hot reload / fresh boot) re-establishes toolkit visibility.
Axis 1 is structurally fixed: the toolkit's analyzer presence is no longer a
one-time side effect that a `seon.eval` edit can drop.

**Single source of truth for the surface.** The var lists must derive from one
place, not be hand-duplicated. Two grounded options:
- read the verb sets from the same place the context section teaches them
  (the namespaces/section data), so "what agents may refer" and "what the
  analyzer declares" can never drift; or
- keep one literal `def` of `{ns -> [public-vars]}` in `seon.eval` and have
  BOTH `declare-toolkit-ns-defs!` and `setup-agent-ns!`'s refer-list read it.
Either way: one list, referenced twice — per the "register once, reference
everywhere" rule. (Today the refer list is an inline string literal in
`setup-agent-ns!`; that becomes data.)

### 2. `setup-agent-ns!` becomes a plain, clean ns establishment

With toolkit `:defs` seeded, `setup-agent-ns!` collapses to standard semantics:

```clojure
(defn ^:async setup-agent-ns! [compile-state agent-ns-sym _agent-id]
  (await (eval compile-state
               (str "(ns " agent-ns-sym " (:require " toolkit-requires "))")
               {:ns 'cljs.user :analyze-deps? true}))
  agent-ns-sym)
```

No bare-`(ns)` prime (the refer form's emit now completes and provides the
object). No `(fn? complete)` probe (the form returns `:ok true`; trust it —
and if it is ever `:ok false`, that is now a REAL error to surface, not a
known-benign one to swallow). The docstring loses the entire "do NOT trust
`:ok`" / goog-provide paragraph and gains one line naming the one self-host
constraint: *refer surfaces of host-bundled nses must be declared to the
analyzer `:defs` (done once in `init-bootstrap!` via
`declare-toolkit-ns-defs!`); aliases need no declaration.*

### 3. Idempotent re-establishment at turn entry (robustness backstop)

Even with toolkit visibility in `init-bootstrap!`, the **home ns itself** is
still established per agent. Make `eval-batch!`'s fold seed (or the turn-entry
path) call `setup-agent-ns!` when `(get-in @cs [::namespaces home])` is nil —
i.e. self-heal if a rebuild dropped the home ns between turns. This is cheap
(O(one clean ns form) and only when missing) and is the same check the
stop-gap uses. It guarantees: a compile-state rebuild mid-session can never
leave an agent verb-less for even one turn. This is the self-host analog of a
JVM REPL re-`refer`-ing core after an `in-ns` — ordinary, not magic.

### Net effect on brittleness

- A future auto-await/`defer`-style edit to `eval.cljs` rotates the version,
  rebuilds compile-state — and `init-bootstrap!` re-seeds toolkit `:defs`;
  the next turn's idempotent `setup-agent-ns!` re-establishes the home ns.
  Verbs never silently vanish.
- No `:ok false` to tolerate; a refer failure is once again a true error.
- No goog-provide prime, no resolution probe — the ns form does what a ns
  form does.

## A nice REPL experience for agents, without magic

- **Default ns is clean and predictable.** The home ns refers exactly the
  taught surface (verbs + `result/<id>` top-level + the data aliases), nothing
  else — like `user` refers `clojure.core`. The context already documents this
  set; now the analyzer and the context agree by construction (one var list).
- **`result/<id>` stays top-level.** Already correct; the home ns defs nothing
  named `result`, so the reserved `result` ns resolves bare. Keep it.
- **Errors are honest.** Removing the `:ok`-tolerance means a genuinely bad
  refer (e.g. a typo'd verb, or a verb removed from the toolkit) surfaces as a
  real error the agent can read, instead of being masked by a probe that only
  checks `complete`.
- **Aliases vs refers, documented.** `:as` aliases (`db`, `schema`, `todo`,
  `message`, `agent`) work with zero `:defs` because `check-uses` only
  validates `:refer`. Only the `:refer` surface (`wait complete pause resume
  terminate`, and any message verbs taught as bare) needs the analyzer seed.
  This is the precise, grounded boundary — not cargo-culted.

## What stays a genuine self-host constraint (named, not cargo-culted)

One, and only one: **host-bundled namespaces ship no analysis cache into the
self-host compiler env, so their `:defs` table is empty, so `:refer` of their
vars throws in `check-uses` (analyzer.cljc:2881/2933) until we declare those
`:defs`.** Real self-host distros avoid it by shipping AOT analysis caches; we
reproduce that with a small, explicit, single-sourced synthetic-`:defs` seed in
`init-bootstrap!`. Everything else (the prime, the probe, the `:ok` tolerance)
was an artifact of leaving that constraint unaddressed.

## Open follow-ups for the implementer (Core lane)

- Decide the single-source-of-truth shape for the toolkit surface (read from
  context-section data vs one literal map) and wire `declare-toolkit-ns-defs!`
  + `setup-agent-ns!` + the context teaching to it.
- Confirm message verbs actually taught as **bare** (`(user …)`) vs aliased
  (`(message/user …)`); declare `:defs` only for the bare-refer surface. (The
  current require uses `[seon.agent.message :as message]` — alias-only — so if
  the context only ever teaches `message/user`, message needs NO `:defs` and
  the only true refer surface is `seon.agent.lifecycle`. Verify against the
  rendered context before trimming the seed.)
- Add a live proof to the Core verification: after a deliberate `init-version`
  rotation, assert `(get-in @cs [::namespaces (home-ns id) :uses])` is
  non-empty on the next turn (the regression this whole rebuild prevents).

## Note on Malli schemas for the proposed fns (Gemini review)

`setup-agent-ns!` and the new `declare-toolkit-ns-defs!` are public — they
need `:malli/schema`. `compile-state` is an opaque self-host compiler atom
(a runtime-value boundary like `budget`'s `inner`), so it specs as `:any`
with the type documented in the docstring; the agent-ns symbol specs as a
named positional. Match the existing `setup-agent-ns!` schema shape
(`[:=> [:catn [::compile-state :any] [::agent-ns-sym :any] [::agent-id :any]] :any]`).

# Parse / read layer — graceful markdown recovery

Second axis of "robust, non-magic agent eval": agents put MARKDOWN into
evals (inline backticks, fenced blocks, pasted prose/output) and the READER
hard-fails with cryptic errors. This section diagnoses the current
`seon.repl.internal/parse-forms` and designs graceful recovery up to a sane
limit. Design only — no src edits.

## What parse-forms does today (grounded in `src/seon/repl/internal.cljc`)

`parse-forms` is a token-at-a-time rewrite-clj scanner. The LOCKED
**forms-and-prose-only** rule (#50/#52): a top-level read form EVALUATES iff
it is a LIST/SEQ (`(…)` + the reader-macros that read as seqs); everything
else is prose. The pipeline:

1. **`strip-code-fences`** runs first: a regex removes fence LINES
   (` ``` `/` ~~~ ` at line start, optional whitelisted lang
   `clojure|clj|cljs|cljc|edn`), leaving the content between fences to read
   normally. So a well-formed ` ```clojure … ``` ` block already "extracts
   the Clojure inside and evals it" — requirement (a) is largely DONE for
   the whitelisted case.
2. **Clean-read prose** is classified by `prose-token?`: a top-level
   `:syntax-quote`/`:unquote`/`:unquote-splicing` (a leading `` ` ``/`~`/`~@`)
   is ALWAYS inline-code prose at the agent REPL (the "backtick cascade" fix)
   → dropped; data literals (`{…}`/`[…]`/`#{…}`) → dropped with one warning;
   bare atoms/tagged literals → dropped.
3. **Reader THROWS** (a token that won't parse) hit the `:error` branch.
   `prose-failure?` decides drop-vs-hard-error: it drops ONLY when the
   reader message matches a NARROW whitelist `prose-error-re`
   (`^Invalid (number|symbol|keyword|token)`) AND the span does not start
   with `(` (`opener-at-start?`). Otherwise it records a `:kind :read
   :ok? false` HARD ERROR the agent must explain.

## Where markdown breaks it — LIVE-PROVEN (pod, 2026-06-28)

```clojure
(parse-forms "(def x 1)\n}\n(def y 2)")
;; }   => {:kind :read :ok? false :error "Unmatched delimiter: } …"}   HARD ERROR

(parse-forms "Here `:seon.db/ok?` matters.\n(def z 3)")
;; line => {:kind :read :ok? false
;;          :error "… Invalid character: ` found while reading keyword."}  HARD ERROR
;;          (this is the EXACT owner-observed failure)

(parse-forms "(defmacro m [x] `(inc ~x))")
;; => ONE {:kind :form}   ;; legit syntax-quote INSIDE a (…) form is UNTOUCHED
```

Diagnosis: both hard-fail because their reader message
("Unmatched delimiter", "Invalid character: `") is NOT in the narrow
`prose-error-re` whitelist, so `prose-failure?` returns false and records a
`:read` failure — even though neither span starts with `(`, i.e. neither is
an intended runnable form. The whitelist is a too-narrow proxy for the real
signal.

Two more latent gaps:
- **Fence lang whitelist too narrow.** `strip-code-fences` only strips lang
  tags in `clojure|clj|cljs|cljc|edn`. ` ```clojurescript `, ` ```text `,
  ` ```output ` etc. don't match → the fence line survives. (Today it often
  still works by accident — three backticks read as a top-level syntax-quote
  token that `prose-token?` drops — but that is fragile, not designed.)
- **A stray trailing ` ``` ` or a fence with an info-string** after the lang
  similarly may not match.

## The key insight — `(`-at-start IS the code/prose discriminator

Under forms-and-prose-only, "the span begins with `(`" already IS the
discriminator between intended code and prose, on CLEAN reads. The fix is to
apply the SAME discriminator at the THROW branch, instead of a narrow
reader-message whitelist:

- **Span starts with `(`** (trimmed) → an intended list form that is
  genuinely broken (`(+ 1 3x)`, a missing paren) → record a `:kind :read`
  failure. Worth surfacing; the agent meant to run it.
- **Span does NOT start with `(`** → it is prose / markdown / a datum / a
  stray delimiter — NOT a runnable form under forms-and-prose-only → DROP
  it gracefully (recover at the next line), NO hard error.

This single rule subsumes the current `prose-error-re` cases AND the
markdown-inline-backtick AND the stray-`}` cases — all three are non-`(`-start
throwing spans. `prose-error-re` can be retired (or kept only as an extra
"definitely prose" fast-path); `opener-at-start?` becomes the primary gate on
both the clean and the throwing path, so the two paths finally agree.

**Why this does NOT break legit code (the careful bit the owner flagged).**
A backtick is syntax-quote — valid Clojure. The rule never touches it when
it is real code: `(defmacro m [x] \`(inc ~x))` reads as ONE `:list` form
(LIVE-PROVEN above) — the inner backtick is bytes inside a `(`-form the
reader handles whole; it never reaches the error branch. Only TWO backtick
shapes are treated as prose, both correct at the agent REPL: (1) a TOP-LEVEL
leading `` ` `` that reads cleanly as `:syntax-quote` (already dropped —
inline-code prose, the locked cascade fix), and (2) a backtick that BREAKS
the read (markdown inline-code around a keyword/symbol) whose span doesn't
start with `(` (newly dropped). A syntax-quote nested in a real list is
neither.

## Design — graceful recovery, in three moves

### Move 1 — extract the code the agent meant from fences (req a)

Harden `strip-code-fences`: drop the lang/info-string whitelist — strip ANY
` ``` `/` ~~~ ` fence line regardless of trailing language or info-string
(`^[ \t]*(?:` ``` `|` ~~~ `).*$`). The content between fences is what evals
(already the case). This makes "a form wrapped in a markdown fence → extract
the Clojure inside and eval it" robust for every language tag, not just five,
and removes the accidental reliance on the backtick-prose path. (Accepted
risk, already noted in the source: a literal triple-backtick inside a Clojure
string is vanishingly rare and was never supported.)

### Move 2 — prose/markdown blocks become prose, not failures (req b)

At the `:error` branch, replace the narrow `prose-failure?` whitelist with
the `(`-at-start discriminator: a throwing span whose trimmed start is not
`(` is DROPPED (recover at next newline via the existing
`next-newline-recovery`), exactly like clean-read prose. Optionally capture
it as a `:kind :comment` narration instead of a silent drop if the owner
wants markdown prose RENDERED back (the entry shape already supports a
comment-only entry) — but to stay consistent with the locked "prose is
DROPPED, not echoed as `;;`" rule (#50/#52, the `;;`-imitation trap), the
default should be DROP, not echo. This kills the cryptic READ ERROR for
`` `:seon.db/ok?` `` -style lines and for stray `}`/`)`/`]`.

### Move 3 — recover from slips UP TO A LIMIT (req c)

The scanner already terminates (every recovery advances the offset
monotonically), so "limit" is about FEEDBACK quality, not loop safety. Add
ONE derived, reactive guardrail: if a reply produced ZERO evaluated forms AND
dropped more than a threshold (e.g. > N spans or > X% of non-whitespace
bytes), emit ONE `:kind :comment` summary entry — "most of this reply was
read as prose/markdown; only forms beginning with `(` are evaluated" — so the
agent gets a SINGLE clear signal instead of silence or a wall of cryptic
errors. Pure function of the parse result, stored nowhere (reactive-context).
This is the "sane limit": below it we silently do the right thing; above it
we tell the agent once, in plain language, what happened and how to fix it
(wrap code in `(`-forms / drop the prose).

### What stays a genuine hard `:read` error (not over-recovered)

A span that DOES start with `(` and fails to read — a truly broken CALL form
(`(db/transact! {:a` with a missing paren) — still records a `:kind :read`
failure. That is the one case where the agent intended to run code and got it
wrong; surfacing it (plus the eval pipeline's parinfer best-effort repair
layered on top) is correct. We are not auto-guessing parens; we are only
ceasing to punish lightly-markdown-flavored PROSE as if it were broken code.

## Net effect

- ` ```any-lang ``` ` fences → the inner Clojure is extracted and run.
- Inline-backtick markdown (`` `:foo` ``, `` `db/query` ``) and pasted prose
  → dropped as prose, no cryptic READ ERROR.
- Stray `}`/`)`/`]` and a lone trailing fence → dropped, not a hard failure.
- Legit syntax-quote inside real forms → evaluates, untouched.
- A genuinely broken `(`-form → still a clear `:read` error (+ parinfer
  repair), as today.
- A reply that is mostly prose → ONE plain-language summary note, not silence
  or N errors.

One discriminator (`(`-at-start), applied uniformly on the clean and the
throwing path — the same "one structural rule over N nominal cases" the
eval-ns rebuild uses.
