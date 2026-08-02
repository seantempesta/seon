---
type: reference
status: abandoned
tags: [reference, agent, history]
---

# Historical Gemini capability survey

> Historical external-API research only. It does not describe a current Seon
> namespace, provider registration, model catalog, or supported feature set.
> Use [[llm-adapters]] for the fresh AI boundary.

This survey examined a deleted `gemini-mcp` path and an older Google SDK. Its
model list, version recommendations, TypeScript examples, and proposed Seon
features were deleted because they were time-sensitive and no current fresh
source consumes them. Git preserves the research snapshot.

Fresh Seon currently emits an OpenAI-compatible request from `seon.ai` through
the JDK HTTP client. A compatible Gemini endpoint can be selected only insofar
as it satisfies that actual request and response contract; there is no native
Gemini capability registry in `src/`.
