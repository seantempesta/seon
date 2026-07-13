# Seon Agent Instructions

You are a subagent working inside the shared Seon repository. `AGENTS.md` is
the universal contract; this file adds only the role-specific rules needed by a
delegated implementation lane.

## Execute the assigned scope

- Work directly on the bounded task the orchestrator gave you. Do not delegate
  or launch more agents.
- Modify only the named files or subsystem. Report useful findings outside that
  boundary with file, line, impact, and the evidence that made them suspicious.
- The worktree is shared. Preserve edits you did not make, stage only your own
  paths when asked, and never switch branches, discard changes, rewrite history,
  or kill processes without explicit coordination.
- Read the current architecture document, component note, active PRD runbook,
  and relevant project skill before changing code. Library behavior comes from
  `reference-code/`, not memory.

## Know the running system

Seon is one application split across two processes:

- the Node ClojureScript pod runs agents, evaluation, context and surface
  derivation, and the Datastar web UI at `http://127.0.0.1:7890`; and
- the JVM `seon.db.server` is the sole durable Datahike writer and committed
  transaction feed/replay source.

The pod reads a local immutable replica and sends writes through the typed
database protocol. `bin/seon` owns both process lifecycles.

Use the shared supervisor for observation:

```bash
bin/seon status
bin/seon logs all 120
bin/seon logs pod 200
bin/seon logs database-server 200
```

Do not start, stop, restart, reset, or delete a database unless the task grants
that authority. If it does, use `bin/seon`; never use `pkill`, raw PID kills, or
manual database-directory deletion. `docs/seon/process-management.md` is the
operator reference.

## Work from evidence

Before editing:

1. Observe the live behavior or reproduce the failure with the narrowest safe
   read-only probe.
2. State what failure would look like after the change.
3. Read the existing implementation and the relevant vendored library source.
4. Try the smallest assumption at the CLJS evaluation seam or against a fresh
   test database when that is safe.

After editing:

1. Run the focused test door for the changed runtime boundary.
2. Exercise the real operation when the task permits it.
3. Try to falsify the result with the failing case and one meaningful edge.
4. Update the current architecture/component note when the public mechanism
   changed, and keep the active PRD roadmap honest.

## Test doors

Use the existing doors; do not create a parallel harness:

```bash
bin/test-cljs --test=seon.example-test
bin/test-cljs --test=seon.example-test/example
bin/test-cljs
bin/test-writer
```

`bin/test-cljs --no-build` is valid only when the existing bundle already
contains the code under test. Do not run overlapping CLJS suites in the live
pod. Model and agent evaluations belong in `src-inspect-ai/`, not bespoke drive
scripts. Read the `clojure-testing` skill before debugging test behavior.

## Web UI verification

The canonical pages are `/`, `/agents`, `/agent/{id}`, `/agent/{id}/debug`, and
`/data` on port 7890. Use the `browser-automation` and `datastar-web-ui` skills.
The browser automation transport cannot prove a long-lived gzip SSE feed; use a
server-side gunzip client and pod logs for feed liveness, then use a real browser
for layout, controls, and console errors.

Vocabulary is precise:

- **canvas** — the one focal agent-controlled view;
- **surface** — any renderable context view;
- **card** — a visual CSS component only;
- **web UI** — the operator/debug interface.

List agents by querying their agent facts directly; do not invent a second
registry or presentation model for that query result.

## Report completion honestly

Return:

- what changed and why;
- exact files changed;
- tests and live proofs with observed results;
- anything incomplete, risky, or intentionally deferred; and
- out-of-scope smells with evidence.

Do not claim completion from code inspection alone. A passing focused test is
necessary; a live proof is required whenever the task changes running behavior.
