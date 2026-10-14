---
title: "DEPS-1: hiccup 2 and Guava in deps.edn (landed by root in the restart batch)"
type: reference
status: done (6a244149d)
created: 2026-10-08
---

# DEPS-1: hiccup 2 and Guava in `deps.edn`

Ledger row DEPS-1 (group O). Owner answer AU-Q7 option 2; root's Guava decision. **Landed by root in the restart
batch**: root staged it in the working tree (uncommitted until the next restart, verified 2026-10-08 against HEAD
`1fcdbf47a`): `deps.edn` adds

```clojure
com.google.guava/guava {:mvn/version "31.0.1-jre"}   ; the version already on the classpath (the :test alias's resolution)
hiccup/hiccup {:mvn/version "2.0.0"}
```

and `reference-code/hiccup` is a new submodule at tag `2.0.0`. No lane claims `deps.edn`; this card records what the
batch carries and how it is proven.

## Why a `deps.edn` change goes through root

Task `seon.cluster.source/a-launch-option-change-in-deps-edn-refuses-every-check`: a `deps.edn` change cannot pass
`bin/seon check` today, so `bin/lane commit` cannot land it. Root commits it with the restart (CLAUDE.md "Root's
batched `start --head` restores the previous commit when a new JVM cannot boot").

## Proof (root, after the restart)

- The candidate boots.
- At the restarted JVM: `(Class/forName "com.google.common.base.Utf8")` answers the class, and
  `(-> com.google.common.base.Utf8 .getProtectionDomain .getCodeSource .getLocation)` names
  `guava-31.0.1-jre.jar` (unchanged from before: the declaration pins what was already resolved);
  `(require 'hiccup2.core)` answers nil and `(str (hiccup2.core/html [:p {:b 1 :a 2} "x"]))` is
  `"<p a=\"2\" b=\"1\">x</p>"`.
- `clojure -Stree` shows one Guava version.

## What this changes for UL-16 (the hiccup row)

AU-Q7 named two gaps; reading `reference-code/hiccup` at 2.0.0 closes one:

- **Attribute and style key order: closed.** `hiccup.compiler/render-attr-map` sorts attributes by name
  (`src/hiccup/compiler.clj:99`) and `render-style-map` sorts style entries (`:65`), so equal values give identical
  bytes without our sort.
- **Style key case: still Seon's.** `render-style-map` writes each key with `util/to-str` (`:61`), so
  `{:fontSize "12px"}` renders `fontSize:12px;`. Seon's `seon.render.hiccup/style-property`
  (`src/seon/render/hiccup.clj:338-352`, React's hyphenation) stays, applied to a style map's keys before hiccup
  sees it (hiccup then sorts the hyphenated names).

So UL-16's wrapper is one function: normalize style-map keys (and the `:class` collection, if hiccup's
`sequential?` join at `:73` does not already match ours), then `hiccup2.core/html`. No attribute sorting.

## Cost

None at runtime (Guava already loaded; hiccup loads when UL-16 requires it).

## Dependencies

Root's restart batch. UL-16 after this.
