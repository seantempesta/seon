---
type: issue
status: active
severity: feature
tags: [issue, agent]
---

# Inspect concurrent attributed agent messages

## Question

Can one resident agent understand and stay on top of several simultaneous
conversations when its transcript contains ordinary timestamped messages with
clear sender and recipient namespace symbols, without a stored conversation or
thread identity?

Deterministic tests can prove message attribution, ordering, wake behavior, and
restart losslessness. They cannot establish whether the resulting transcript
is clear to an actual model. This behavioral question belongs in the existing
`src-inspect-ai` task and scorer system.

## Intended task

After namespace-addressed agents and send-or-create messaging land, add one
native task under `src-inspect-ai/src/seon_inspect/tasks/`. The live driver
creates several named specialists, has them concurrently send distinguishable
requests to one resident agent, and then gives that agent enough turns to
address every request. A restart variant interrupts the recipient with accepted
messages outstanding.

The task text explicitly tells the recipient to follow multiple simultaneous
conversations and stay on top of all of them. It does not coach a hidden
conversation-grouping strategy.

## Scoring evidence

The scorer reads database facts rather than trusting the agent's narrative:

- every accepted message retains its exact sender, recipients, timestamp, and
  content;
- every required request receives a substantively correct response attributable
  to the recipient;
- no sender's facts are answered as though they came from another sender;
- messages accepted during active work appear on a later turn;
- restart preserves every unanswered message; and
- the recipient's final direct assessment records whether attribution was
  clear, what was easy to miss, and the smallest presentation improvement it
  would request.

The subjective assessment is report-only. Correct coverage and attribution are
the scored outcome. Run several epochs and at least two supported models before
changing the data model. Improve transcript presentation first if failures show
confusion; add stored structure only after repeated evidence demonstrates that
ordinary message facts are insufficient.

## Acceptance

- Offline good and bad fixtures prove the scorer discriminates complete,
  correctly attributed work from missed or conflated requests.
- The live task uses real Bun execution children and the ordinary database
  message/wake path.
- One run covers concurrent existing specialists; another covers automatic
  creation of a previously absent namespace-addressed specialist.
- The restart arm proves read-back and completion without an alternate message
  runner or transcript implementation.
