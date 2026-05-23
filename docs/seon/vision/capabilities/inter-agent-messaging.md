---
type: capability
status: not-started
tags: [vision, agent, flow]
---
# Inter-Agent Messaging

Agents communicate asynchronously via typed mailboxes. Feature requests, bug reports, schema change notifications, and status updates are Malli-spec'd maps that persist in Datahike and survive restarts. This closes the feedback loop where one agent's change triggers another agent's response.

## Event-Driven Message Routing

Messages are Malli-specced maps routed by schema shape, not by explicit address. When an event occurs in Seon (data change, user action, schema update, test failure), the system constructs a typed message and routes it to affected namespaces. The routing uses the same specificity algorithm as [[concepts/renderer-discovery]]:

1. Construct the message with full Malli spec.
2. For each target namespace, find functions whose input schema matches the message shape.
3. If a handler exists, invoke it. The most specific match wins.
4. If no handler exists, apply the smart default for that message type.
5. If the message requires acknowledgment and no handler exists, wake the namespace's agent.

The agent-fallback behavior is central to the [[concepts/progressive-enhancement]] philosophy. Agents don't need to anticipate every possible message. They can be woken on demand, inspect the message, and decide whether to implement a handler (making future messages automatic) or handle it as a one-off. Over time, frequently-received messages accumulate specific handlers, and the namespace becomes increasingly autonomous.

### Message Flow Examples

| Event | Message Type | Default Behavior | Agent-Built Handler |
|-------|-------------|-------------------|---------------------|
| Upstream schema changed | `::schema/change-notification` | Log warning | Migrate local data, update dependents |
| Test failed in dependent | `::test/failure-notification` | Add to notifications list | Auto-investigate, attempt fix |
| User clicked UI element | `::ui/click-event` | No-op | Custom interaction logic |
| Data changed in the database | `::db/change-notification` | Ignore | Recalculate derived state |

### Smart Defaults Philosophy

Every message type has a default handler that produces a reasonable result. The system never blocks on missing functionality. Defaults are not error states -- they are functional behaviors that keep the system running while agents progressively add specificity. See [[concepts/progressive-enhancement]] for the full pattern.

## What Exists

Nothing beyond the flow topology routing infrastructure. No mailbox abstraction, message types, or delivery semantics are implemented. The [[concepts/feeds]] and [[concepts/subscriptions]] concepts describe the transport mechanisms that would carry these messages.

## Gaps

- No mailbox abstraction for agents
- No message type schemas
- No delivery, persistence, or replay semantics
- No notification routing when upstream schemas change
- No agent-fallback mechanism (wake agent when no handler exists)
- No smart default registry for message types

## Related

- Components: [[components/flow-topology]], [[components/agent-system]]
- Concepts: [[concepts/progressive-enhancement]], [[concepts/feeds]], [[concepts/subscriptions]]
- PRDs: [[prds/super-repl/prd]]
