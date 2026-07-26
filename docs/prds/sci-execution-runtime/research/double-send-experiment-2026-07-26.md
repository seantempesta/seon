---
type: research
status: active
tags: [research, agent, runtime, database]
---

# Agent-message double-send experiment (2026-07-26)

## Verdict

The named A-to-B kill experiment is **not executable at revision
`472797ffc8f4ab432e7a206e00e3ca832e0a4fe1`**. The current JVM SCI context does
not expose `seon.agent.message/agent`, so no production form can commit an
agent-to-agent message before its terminal receipt. The direct current-runtime
probe returned `Unable to resolve symbol: seon.agent.message/agent`.

The current lifecycle reply does take a freshly allocated message id, but it
does not have the kill window named by the experiment. Its message, terminal
eval receipt, turn completion, and run closure are built into one transaction.
A process cannot be killed after that message commits but before that run
closes: those datoms commit together or not at all.

Therefore this experiment neither confirms nor refutes double-send for the
future callable messaging path. The UNKNOWN remains open, with an explicit
dependency on plan step 1 making messaging callable through the JVM effect
path. No synthetic binding was injected because that would test a mechanism
that is not today's runtime.

## Dependency ledger

| Dependency or owner | Revision read | Evidence used |
|---|---|---|
| Seon working tree | `472797ffc8f4ab432e7a206e00e3ca832e0a4fe1` | `seon.sci.ctx/base`, `seon.agent.driver/{lifecycle-tx-data,execute-form!,allocated-transact!}`, `seon.agent.run.core/finish-tx-data`, and `seon.eval.receipt/receipt-id`. |
| SCI | `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | `reference-code/sci/src/sci/core.cljc:277-283` defines supplied namespaces; `reference-code/sci/src/sci/impl/resolve.cljc:323-334` rejects an unresolved symbol. |
| Datahike | `caf526850084a9d5846ccd9ea34251fe411e0d6b` | `reference-code/datahike/src/datahike/db/transaction.cljc:1195-1248` folds the submitted transaction data into one resulting `db-after`. |
| Wake fix | `4dbaeda0ef905c07600637e86df5d5de8fc7e725` | The driver now enumerates addressed candidate messages and applies `waking-inbound?`, so a future duplicate A-to-B message will be observable by B. |

## Fresh-id allocation site

`lifecycle-tx-data` builds the reply row with the local placeholder id
`"seon.agent.driver/message"` at
`src/seon/agent/driver.clj:92`. `allocated-transact!` then requests a generated
`:seon.agent.message/id` at `src/seon/agent/driver.clj:403-404` and replaces
the placeholder with that fresh candidate at
`src/seon/agent/driver.clj:409-411`.

The allocator does not commit the candidate separately. It calls the supplied
transaction builder and submits the resulting transaction once
(`src/seon/db/id.cljc:1407-1418`). The fresh identity is therefore real, but it
does not itself create a partial message-before-close commit.

## Preflight method and evidence

### 1. Read the production SCI binding table

`seon.sci.ctx/base` supplies only:

- `clojure.core`;
- `clojure.string`; and
- `seon.agent.lifecycle` with `wait`, `complete`, `pause`, `resume`, and
  `terminate`.

There is no message namespace, binding, dispatcher, or load function
(`src/seon/sci/ctx.clj:15-35`). This agrees with the plan's current-state
inventory at `plan/README.md:177-180`.

### 2. Execute the requested send form through today's evaluator

The exact preflight used the current JVM evaluator and the same shared SCI base
as the run driver:

```clojure
(require '[seon.sci.eval :as sci.eval])
(sci.eval/open! {:seon.sci.eval/concurrency 1})
(sci.eval/evaluate
 {:seon.sci.eval/source
  "(seon.agent.message/agent \"agent-b\" \"probe\")"
  :seon.sci.interrupt/time-limit-ms 1000})

```

Observed result, reproduced three times in one fresh JVM:

```clojure
{:trial 1
 :message "Unable to resolve symbol: seon.agent.message/agent"
 :fn-entries 0
 :duration-ms 7}
{:trial 2
 :message "Unable to resolve symbol: seon.agent.message/agent"
 :fn-entries 0
 :duration-ms 0}
{:trial 3
 :message "Unable to resolve symbol: seon.agent.message/agent"
 :fn-entries 0
 :duration-ms 0}

```

This fails before a message capability request, message transaction, or sleep
can occur. Repeating a kill cycle cannot add evidence because every attempt
fails at symbol resolution before the proposed kill window.

### 3. Locate the current reply transaction boundary

For a completed lifecycle value, `lifecycle-tx-data` returns one transaction
data vector containing:

- the run pointer and claim-epoch fences;
- `:seon.agent.run/status :closed`;
- removal of `:seon.agent.run/process`;
- removal of the agent's `:seon.agent/run` connection;
- `:seon.agent.turn/status :done`;
- the run result; and
- the agent-to-user reply message.

`execute-form!` concatenates the terminal receipt data and this admitted data
before making the one terminal transaction call
(`src/seon/agent/driver.clj:283-293`). `allocated-transact!` replaces only the
message placeholder inside that complete transaction data vector
(`src/seon/agent/driver.clj:392-413`).

Consequently the observable states for today's lifecycle reply are:

| State | Terminal receipt | Reply message | Run |
|---|---|---|---|
| Before terminal transaction | absent or `:running` | absent | `:open` |
| After terminal transaction | terminal | present once | `:closed` |

There is no state with the reply message present and the run still open.

## Requested crash evidence

No host process was killed and no throwaway cluster was created. The required
send-commit state is unreachable in the production runtime at this revision,
so a kill would test only process recovery without having performed the
subject operation.

All three preflight trials produced zero function entries. Because they failed
before a message request, the subject-operation evidence counts are:

| Evidence | Trial 1 | Trial 2 | Trial 3 |
|---|---:|---:|---:|
| A-to-B message datoms | 0 | 0 | 0 |
| A terminal receipts after a send | 0 | 0 | 0 |
| B runs caused by the send | 0 | 0 | 0 |
| B transcript occurrences | 0 | 0 | 0 |

These zeroes are a failed experiment precondition, not evidence for
exactly-once delivery.

The repository operator was already down before the preflight and remained
down. No default or named database was opened, reset, or changed.

## Experiment to run when the dependency lands

Once plan step 1 exposes `message/agent` through the production JVM effect
path, repeat the named experiment without a synthetic binding:

1. Reset, apply, and open a throwaway named cluster.
2. Create A and B and commit A's plan with a form that sends to B, then remains
   inside the same nonterminal form long enough to expose the kill window.
3. Query and retain A's running receipt and the committed A-to-B message.
4. Kill the host workload process with `SIGKILL` before A's terminal receipt.
5. Restart, wait for lease recovery and re-execution of the first ordinal
   without a terminal receipt, then query all A-to-B message ids and B runs.
6. Repeat three times. A valid verdict reports the per-trial sending receipt,
   message ids and transaction ids, A recovery epoch, B run causes, and B
   transcript occurrences.

If re-execution produces two messages, the acceptance condition already named
by the plan is message identity derived from the sending receipt
`(run, ordinal, epoch)`, followed by the same crash experiment proving one
delivered message and one B run.
