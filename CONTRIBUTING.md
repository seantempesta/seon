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

Read [`AGENTS.md`](AGENTS.md) first — it is the maintained contributor orientation
(conventions, the dev hook, the testing model, and the architecture). The
active runtime is the **CLJS pod** (a long-running Node process, web UI on
`http://127.0.0.1:7890`) backed by the JVM Datahike database server.
`bin/seon` manages the complete watcher, writer, and pod graph—use `up`,
`status`, `logs pod --follow`, and `restart`. For the mental model, start at
[`docs/seon/architecture/architecture.md`](docs/seon/architecture/architecture.md).

## Practical contribution guidelines

- Open an issue before substantial work so we can align on direction.
- Match the existing style (Clojure conventions, fully-namespaced keyword maps,
  Malli specs on every public fn). Schemas live with the namespace that owns the
  data; register via `seon.schema/register!`.
- Keep commits focused and well-described.
- **Run the relevant tests before opening a PR.** Use `bin/test-cljs` for the
  CLJS pod, `bin/test-writer` for the JVM database server, and
  `bin/seon test operator` for Babashka operator code. Run focused checks while
  iterating and one complete checkpoint at the unit boundary.

## Gotchas for contributors

These are documented for agents in `AGENTS.md` but easy to trip over as a human:

- **Malli instrumentation is on in normal development — a wrong
  `:malli/schema` fails at runtime, not at lint time.** Public functions are
  validated across their supported boundary. If you see a
  `:malli.core/invalid-output`, fix the schema or the caller — it's a real
  mismatch. An `^:async` fn returning a `js/Promise` is a known sharp corner
  (see the `clojurescript` skill). `SEON_INSTRUMENT` is an emergency
  stability kill-switch, never a way to suppress a mismatch. Details:
  `AGENTS.md` → "Function Instrumentation".
- **Never `git add -A`.** The working tree is shared by multiple concurrent
  agents; sweeping everything tangles their uncommitted work. Stage explicit
  pathspecs (`git add path/to/file …`).
- **The pod can wedge.** Overlapping `cljs.test` runs or a never-resolving
  Promise can jam the pod's shared async continuation — recover with
  `bin/seon restart` (a pristine run), or `bin/seon cluster reset default`
  for a fresh database.
