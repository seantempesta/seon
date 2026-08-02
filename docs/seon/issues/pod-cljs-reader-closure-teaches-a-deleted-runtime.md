---
type: issue
status: open
severity: friction
tags: [issue, deletion, documentation, tooling]
---

# Delete the pod CLJS reader closure

## Problem

The root CLJS build is explicitly dead, but a transitive reader closure still
teaches and pins it as an available downstream runtime. The readers are not
evidence that the build remains live: they are stale documentation, examples,
ACME composition, and evaluation-admission rows whose own claims depend on the
deleted pod.

`package.json` is mixed ownership. Its Tailwind slice is live; its client
scripts and non-CSS dependency closure belong to the deleted pod. Treating the
whole file as dead would break CSS, while treating the live CSS reader as a
reason to retain every package would preserve the pod by accident.

## Evidence

- `deps.edn:179-211` says the `:cljs` alias is dead and nothing may invoke it,
  but `docs/seon/components/extra-src.md:37-104` actively instructs downstream
  users to build AOT CLJS into a pod through `clj -M:cljs` and even names the
  deleted `bin/test-cljs`.
- `shadow-cljs.edn:1-40` consumes that alias and describes a current Shadow
  runtime; `:57-156` declares five pod/self-host builds whose `:main`
  namespaces no longer exist under fresh `src/`. Its only `externs/` reader is
  its own four `:externs ["externs/node_fs.js"]` declarations at `:81`, `:112`,
  `:137`, and `:150`.
- `docs/seon/reference/third-party-integration.md:48-101` advertises
  `examples/third-party-override/` and `SEON_EXTRA_SRC` / `SEON_EXTRA_PRELOAD`
  as the supported core-override mechanism. The example itself contains only
  CLJS and tells users to restart the build and pod
  (`examples/third-party-override/README.md:21-38`).
- The same deleted path remains active-looking in
  `docs/seon/reference/third-party-setup.md:25-26`,
  `docs/seon/process-management.md:94`, `.env.example:163-166`, and the
  maintained `acme/` CLJS package. The already-open
  [[acme-wrapper-speaks-deleted-operator-command-language]] owns ACME's operator
  conversion; this issue owns the common CLJS closure it reads.
- `src-inspect-ai/evaluation-sources.lock.json:45-66` admits
  `shadow-cljs.edn`, `package.json`, and a nonexistent `package-lock.json` as
  runtime build inputs. `src-inspect-ai/tests/test_source_admission.py:126-135`
  asserts those literal paths. Source admission therefore pins the stale
  roster; it does not prove the admitted mechanisms execute.
- `package.json:10-15` mixes live CSS commands with dead `client:watch`,
  `client:run`, and `client:clean` commands. `:21-39` likewise mixes the three
  CSS packages with Shadow, browser/pod, and Node SDK dependencies that have no
  fresh-source consumer. In contrast, `bin/css:1-50` invokes only
  `@tailwindcss/cli`; `resources/public/css/input.css:7-8` reads Tailwind and
  the typography plugin, and the fresh render work repeatedly runs `bin/css`.

## Owner

The pod deletion wave, with the downstream extension contract redesigned at
the surviving database program-graph owner rather than translated into
another build overlay.

## Acceptance

- Delete `shadow-cljs.edn`, `externs/`, the `deps.edn` `:cljs` alias, and every
  active instruction/example/evaluation-admission row whose only behavior is
  the pod/Shadow path, outermost misleading readers first.
- Delete the client scripts and non-CSS package dependency closure while
  preserving one reproducible `bin/css` Tailwind installation and build.
- Reconcile the existing ACME issue against the surviving JVM/downstream
  contract; do not retain `SEON_EXTRA_*` as a compatibility path.
- A repository-wide reference chase finds no active pod, Shadow, `clj -M:cljs`,
  `SEON_EXTRA_SRC`, `SEON_EXTRA_PRELOAD`, or `externs/node_fs.js` instruction.
- `bin/css` still builds the fresh web stylesheet from a clean install.
