# next — laws and how to work

This file is a stub. It will be filled from lane F2's "How to write a proc" section
(`../notes/option3-F1-flow.md`, revised in place by F2) and from Seon's own `CLAUDE.md`
minus the SCI and branch-runtime laws. Until then the owner's decisions in
`../notes/DECISIONS.md` are the authority.

## DRAFT: step-fn rules (copied from `../notes/DECISIONS.md`, owner 2026-10-20)

"No point in using flow if we are going to use it the wrong way." Every proc follows these rules, and F2 audits each one against them:

1. **The step-fn is a Var** (hot reload), one fn with the four arities. `describe` declares `:params`, `:ins`, `:outs`, `:workload` and a deliberate `:ping-map-fn`.
2. **`transform` is a function of `(state, in, msg)` returning `[state' outputs]`.** An effect is an output sent to the `:io` proc that owns it. Only that proc performs it (the writer's transact, a socket write, a fork).
3. **Workload is explicit and honest:** `:io` does no long computation, and `:compute` never blocks.
4. **No blocking on another proc inside a step:** no promise deref, no `<!!` and no RPC. Request/reply is a message out, a pending entry in state keyed by request id, and a message back.
5. **State is plain data, validated by its schema.** A live host object (connection, socket, listener) lives only in the proc that owns it: opened in `init`, released in the `::flow/stop` transition. Pause and resume transitions mean "stop admitting work" and "admit again".
6. **Outputs are data,** never closures.
7. **Errors:** an interop edge returns its typed error as data. A defect throws to Flow's error channel, and root records it.
8. **Channel buffers are bounded,** sized by loss semantics.
9. **No thread, future, go-loop or atom outside procs.** An edge reader thread exists only for a blocking read, and it only feeds a proc's in-port.
10. **Contracts on every arity.** Each step-fn is unit-tested as a plain function, calling its arities with data and no graph.
11. **The step-fn contract is the process abstraction.** Root's procs run it under Flow. The single-threaded fork server and agents run the same contract in their own loop, receiving pause, resume, stop and ping as messages.

## DRAFT: the process model, in one paragraph

Every process is a Flow proc: writer, fork server, web server, reconciler, and each agent
through its stand-in proc in root. Ordering is dataflow: no declared start order, procs park
until their inputs bring work, and shutdown closes the entrances and lets "done" flow
downstream, the writer draining last. The one ordering constraint is physics: the fork
server is forked at boot before any thread exists. There is one serial writer proc, the only
`d/transact` caller, with group commit. No direct `transact`, no `d/listen`, no state in atoms
beside a proc, no raw threads, futures or go-loops outside procs; an exception must cite physics.

## Working in this repo

- Forks live in `reference-code/` on `jolt/main`; a fix is a `jolt/<topic>` branch in the fork,
  merged into `jolt/main` by root once measured, then the submodule pin moves. Never a branch
  named `seon*` (`~/src/seon` uses those on the same GitHub forks).
- `bin/build` builds the runtime from the pins only; `bin/test` runs `test/`.
- Commit explicit paths only; never push.
