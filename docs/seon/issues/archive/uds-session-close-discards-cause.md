---
type: issue
status: resolved
severity: high
tags: [issue, database, flow, agent]
---

# Preserve the cause when a UDS session closes

## Problem

The JVM UDS request server closes a multiplexed pod session after several
decode, worker-admission, and selector failures without retaining the cause.
Every pending pod request then receives only `Database authority ended the
session`, so a real concurrency failure cannot be distinguished from invalid
bytes, bounded-capacity rejection, handler admission, or selector failure.

## Evidence

On 2026-07-18, four concurrent public `/agents/run` requests used one ready pod
and one live writer. Three agents completed correctly in 12.5–16.4 seconds;
`happy-waves-brake` closed as `:error` after 2.1 seconds before opening a turn.
The pod recorded an ended UDS session and failed reads for many retained agents,
then reconnected and completed the other three requests. The writer stayed
ready and its log retained no exception.

`seon.db.transport.uds/admit-payload!` discards a decode or handler exception,
closes on request-worker rejection, and `process-selected!` discards selector
processing exceptions. All converge on the same peer-visible closed-session
error. Bounded cause logging has been added so the live drive can identify the
actual owner before changing capacity or retry behavior.

The source audit identified the matching silent path. The Bun client admits 256
pending requests on one multiplexed session, while the JVM defaults to 64
response slots per session. The server reserves one slot after reading a frame
header; when request 65 arrives before an earlier response releases its slot,
`read-session!` treats the temporary reservation miss as fatal and closes the
socket. Input and addressed-event pressure have similar fail-closed paths, but
the 256-versus-64 mismatch is the shortest deterministic falsifier for this
drive.

## Owner

`seon.db.transport.uds` owns framed request admission and session closure.
`seon.db.writer/handle-request!` owns semantic database failures and must return
them as protocol data instead of terminating transport.

## Acceptance

- Every non-peer UDS session close retains one bounded cause and closure path.
- Temporary response-slot or input-byte pressure pauses socket reads and resumes
  them when bounded capacity is released; malformed and oversized frames still
  fail closed.
- The four-way public agent drive either keeps its one database session or
  names the exact bounded resource or invalid request that ended it.
- Capacity pressure returns an ordinary busy/error value when the protocol can
  still respond; it does not silently destroy unrelated pending requests.
- All four agents reach a terminal turn with exact replies under the supported
  concurrency bound, and reconnection does not redrive every retained idle
  agent unnecessarily.

## Resolution

Commit `0c510a01` retains a valid frame header and pauses socket reads when
bounded request capacity is occupied, then resumes the same session when input
bytes or response slots are released. The focused UDS suite passes 31 tests and
157 assertions. Commit `92575de7` retains every event-delivery pressure status;
the next live drive proved the remaining closes were `send/session-full` from
per-agent wake listeners.

Commit `e679d082` resolves each hosted agent's entity ID once when installing
its existing wake listener and includes that value in the
`:seon.agent.message/to` datom pattern. Datahike now selects only the target
agent's listener before crossing the socket. Four fresh concurrent public
agents then completed with exact replies, terminal turns, and valid historical
model evidence. The writer logged no event pressure or transport failure, and
the watcher, writer, and pod remained ready.
