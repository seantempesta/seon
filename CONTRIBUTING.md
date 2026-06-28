# Contributing to seon

Thanks for your interest in seon. A few things to know before contributing.

## Project status

seon is primarily a personal research project by Sean Tempesta. It's published under AGPL-3.0 as prior art and as a reference for ongoing work. Outside contributions are welcome but not expected — there's no obligation to land them on a particular timeline, and direction may shift unilaterally as the underlying research evolves.

## License

seon is released under [AGPL-3.0](LICENSE). By contributing to this project, you agree to the inbound-license terms below.

## Inbound license (required for all contributions)

> By submitting a contribution to this repository, you license your contribution to the project under the same license as the project itself (AGPL-3.0), and additionally grant Sean Tempesta Consulting LLC a perpetual, worldwide, non-exclusive, royalty-free license to relicense your contribution under any other terms.

This dual grant lets the project (a) accept your contribution under its current open-source license while (b) preserving Sean Tempesta Consulting LLC's ability to dual-license seon commercially or relicense the project as a whole. If you can't agree to this, please don't submit a contribution.

By opening a pull request, issue with attached code, or otherwise submitting work to this repository, you affirm that you've read and accepted these terms.

## Commercial / non-AGPL licensing

If AGPL-3.0 doesn't fit your use case (e.g., you'd like to use seon in a proprietary or closed-source product), commercial licensing is available — contact Sean Tempesta Consulting LLC.

## Orientation

Read [`CLAUDE.md`](CLAUDE.md) first — it is the real contributor orientation
(conventions, the dev hook, the testing model, and the architecture). The
active runtime is the **CLJS pod** (a long-running Node process, `src/seon/*.cljs`,
inspector UI on `http://localhost:7890`) backed by the `wire-server` datahike
writer; the JVM main-app (`src/seon/*.clj`) is a **paused** track. `bin/seon`
supervises both — `bin/seon start all`, `status`, `tail pod`, `restart pod`.
For the mental model, see [`docs/seon/architecture/overview.md`](docs/seon/architecture/overview.md).

## Practical contribution guidelines

- Open an issue before substantial work so we can align on direction.
- Match the existing style (Clojure conventions, fully-namespaced keyword maps,
  Malli specs on every public fn). Schemas live with the namespace that owns the
  data; register via `seon.schema/register!`.
- Keep commits focused and well-described.
- **Run the tests before opening a PR.** The active CLJS-pod suite runs via
  `bin/test-cljs` (a fresh `cljs.test` JVM, ~160 s). The paused JVM track's tests
  run inside its REPL (`(user/run-tests)`), not via a separate process.

## Gotchas for contributors

These are documented for agents in `CLAUDE.md` but easy to trip over as a human:

- **Malli instrumentation is always on — a wrong/absent `:malli/schema` throws
  at runtime, not at lint time.** Every public fn is validated (inputs, output,
  arity) on every call; there is no "off" mode. If you see a
  `:malli.core/invalid-output`, fix the schema or the caller — it's a real
  mismatch. An `^:async` fn returning a `js/Promise` is a known sharp corner
  (see the `clojurescript` skill). Details: `CLAUDE.md` → "Function Instrumentation".
- **Never `git add -A`.** The working tree is shared by multiple concurrent
  agents; sweeping everything tangles their uncommitted work. Stage explicit
  pathspecs (`git add path/to/file …`).
- **The pod can wedge.** Overlapping `cljs.test` runs or a never-resolving
  Promise can jam the pod's shared async continuation — recover with
  `bin/seon restart pod` (a pristine run), or `bin/seon cluster reset default`
  for a fresh world.
