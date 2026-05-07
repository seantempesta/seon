# Personal/Work AI Separation + Per-User Sandbox Runtimes

**Date:** 2026-05-07
**Threads:** CLAUDE.md open questions Q4 (separation pattern) and Q7 (sandboxed runtimes). Adjacent: MCP as the interposition seam.
**Method:** Gemini CLI agentic web survey (gemini-3-flash-preview), cross-checked against what I know is actually shipping vs press-release.
**Bottom line up front:** Nobody is shipping the *full* personal-AI-fronts-corporate-AI pattern in production with paying enterprise customers as of early 2026. The pieces exist — MCP gateways, prompt-rewriter proxies, browser sidebars, microVM sandboxes — but they are sold one piece at a time, mostly to developers building agents, not to end users protecting personal context from their employer. the agent's wedge is integration, not invention. The architectural seam to bet on is **MCP-proxy + Firecracker microVM sandbox per user**, not V8 isolates and not browser extensions.

---

## Framing

The product promise is structural: the user's mental state never touches the employer's substrate, but the user still gets help on work tasks. That requires a trust boundary the user controls and the employer cannot subpoena, log, or inspect. Three architectural questions fall out:

1. **Where does the boundary live?** Browser? OS? A proxy in front of the work AI? A separate agent that *uses* the work AI as a tool?
2. **What runtime hosts the personal agent's code, persistently, per user, at scale?** Because eventually each the agent instance is going to want to write and run code that survives across sessions — a notebook the agent keeps for its user.
3. **What protocol does it speak to the work AI?** MCP is becoming the answer by default, which both helps and constrains us.

None of these are theoretical. Each has a small graveyard of attempted products already.

---

## Existing personal-vs-work AI separation patterns — who's actually shipping

The cleanest finding from the survey: **the full pattern (personal AI that genuinely mediates a corporate AI on behalf of the user, with the employer unable to see the personal layer) is not shipping in production anywhere.** Adjacent products ship pieces of it:

- **Browser sidebars / agentic browsers.** Dia (The Browser Company), Comet (Perplexity), Arc Max before it. These intercept *web-based* AI surfaces by sitting in the same DOM. They can inject context and they can read what's on the page. They are personal-context-rich, but they don't really *separate* anything — the corporate AI sees whatever the user types into its textarea, and the browser-side memory is at the mercy of MDM policies that often block extensions outright. Useful as inspiration for the UX seam; weak as the trust boundary.
- **Bardeen, MultiOn, Lindy, Adept-style agent platforms.** Standalone agent loops that script SaaS apps via headless browser or API. Closest in shape to "personal agent that uses work tools," but the "personal" part is shallow — they're task-execution layers, not memory-rich models of the user. None of them claim the privacy promise.
- **Privacy-first personal AIs.** Personal.ai, Khoj, Mem.ai, Saner.ai, Granola, Limitless (the company; the Pendant hardware is dead, folded into Meta wearables per the survey). These build the personal-memory side credibly but don't mediate any corporate AI. They're parallel, not interposed.
- **Prompt-rewriting / DLP middleware.** Martian (Airlock), Helicone, PromptLayer, plus enterprise DLP vendors like Nightfall, Strac, BigID. Real product, real enterprise sales — but the buyer is *the employer*, scrubbing PII from prompts going out to OpenAI/Anthropic. The promise is inverted from the agent's: it protects the company from the user, not the user from the company.
- **OS-level shims.** Apple Intelligence with App Intents and Private Cloud Compute is the only at-scale production example, and Apple owns both ends — they are not a model anyone else can replicate without owning the OS. Meta is consolidating its always-on memory play into Ray-Ban + Horizon. This route is closed to startups.
- **MCP gateways / registries.** Smithery.ai, Klavis, Composio, Pica, MintMCP, Arcade.dev, IBM ContextForge, Red Hat Connectivity Link. These are the freshest entrants and the most relevant. They are real software with real adopters, but the use case being sold is "let one agent talk to many tools cleanly," not "interpose a personal layer between user and corporate agent." The pattern is *available*; nobody's *productizing it for the user-protection use case*.

**So: the gap is real.** The reason nobody ships it is partly chicken-and-egg (corporate AIs that use MCP weren't widespread until ~2025), partly that the buyer is unclear (the user wants it, the employer doesn't, IT won't whitelist it, and consumers won't pay for what they don't yet feel they're losing), and partly that the technical pieces only just lined up.

That gap is also the risk. If the buyer-unclear problem is structural — IT will block any tool that hides user state from the employer — then the architecturally clean version of the agent is unsellable to enterprises and only works as a BYOD consumer product over which the employer has no policy control. Worth flagging as an open strategic question.

---

## Architectural options compared

Five archetypes, scored on the dimensions that matter for the agent specifically. Blast radius means: if this layer is compromised, what does the attacker get?

| Archetype | Trust boundary | Blast radius if compromised | Latency cost | Can it actually withhold? | BYOD vs MDM |
|---|---|---|---|---|---|
| **Browser extension / agentic browser** | Weak. Content-script access is broad; hard to prove negative claims about what it sees. | High — work cookies, session tokens, full DOM of every tab. | Low (client-side). | Only for web surfaces. Useless against desktop apps and IDE-embedded copilots. | BYOD only. MDM-managed browsers routinely block extensions. |
| **Prompt-rewriter / DLP proxy** | Medium. Heuristic; failure modes are silent (over-redact or leak). | Low — text in flight only, no persistent state. | High — adds an LLM hop. | Variable. Honest answer: scrubbing fights a losing battle against context leakage in long conversations. | Easy to deploy as an HTTP proxy; hard to *force* user traffic through it without browser/OS cooperation. |
| **MCP gateway / proxy** | Strong. Schema is explicit; what's exposed is auditable per-tool. | Medium — restricted to whitelisted tools, but the proxy holds tokens. | Moderate (one extra JSON-RPC hop). | High — can mask, filter, or augment any tool result before it reaches the work model. | Natural MDM fit if IT is on board; viable BYOD if user supplies their own MCP-using client. |
| **Sidecar agent that uses corporate AI as a tool** | Strongest. The user runs the loop; the corporate AI is just an API endpoint the personal agent calls. | Highest — the agent *is* the user; it holds all credentials, personal memory, work tokens. | Low (local or private cloud). | Perfect — by construction. Personal context only flows to the work AI when the agent decides to push it. | "Shadow AI." Hard to deploy inside MDM environments. Pure-BYOD play. |
| **OS-level shim** | Strongest in theory. | Total — same as the OS. | Lowest. | Best (everything is on-device). | Only viable if you own the OS. Closed route for the agent. |

The honest pairwise read: **MCP gateway** is the cleanest *technical* seam — explicit schema, auditable, defensible in a security review. **Sidecar agent** is the cleanest *philosophical* seam — the user is in charge of the loop, not the employer. They are not mutually exclusive: the sidecar agent can *use* an MCP gateway as its tool layer. That combination is what I'd build.

Browser extensions are tempting because they're easy to demo, but the trust story is bad and MDM blocks make them unsellable into the enterprise context that's most of the market. Prompt-rewriters are a feature, not a product — useful as a defense-in-depth layer inside the sidecar, not the architectural foundation.

---

## Sandbox runtime survey

The premise: each user eventually has an the agent instance that holds long-lived state (the Datomic-style fact graph) and can run code (probably JS) the agent has written for that user, with persistence across sessions. Survey of substrates, with cost-at-scale honesty:

### V8 isolates — Cloudflare Workers + Durable Objects, Deno Deploy, Vercel Edge

- Density is the headline: ~10K isolates per 16 GB of host RAM, sub-5ms cold start. Per-user-month cost is fractions of a cent at request-driven workloads.
- **Statefulness story is the catch.** V8 isolates are request-scoped by design. Durable Objects bolt persistent state on the side via a separate single-threaded actor with attached storage; Deno KV similarly. These are *storage* attached to *ephemeral compute*, not "the agent has a long-running process." Anything that wants to maintain in-memory state across calls (a loaded model, a warm DB connection, a long-running computation) is fighting the model.
- For the agent, this means V8 isolates are great if the agent is "function of (DB state, current message) → response, with no in-memory persistence between turns." That's actually plausible for V1. It is not great if the agent ever wants to run sustained background work (e.g., periodic re-summarization, async tool calls, web crawls that take minutes).
- **Verdict:** good fit for the request-handler tier; not the right home for "the agent's persistent runtime environment."

### Bun in containers

Just Node-but-faster in a container — same operational model as any container platform. No special isolation story. Skip.

### Firecracker microVMs — Fly.io Machines, AWS Lambda (under the hood), Koyeb, E2B, Modal

- Hardware-virtualized, ~125ms cold start, ~5 MB overhead per VM. Density ~1K VMs per host (vs 10K for isolates).
- **Snapshot-and-thaw is the unlock.** The VM's full memory state goes to disk when idle, restores in 100–200ms on demand. Fly.io Machines and AWS Lambda SnapStart both do this in production. Cost model becomes "pay for active seconds, not idle seconds," which is the right shape for an agent that's idle 99% of the time.
- E2B and Modal have explicitly productized this for agent code execution. Modal in particular has reasonable per-second pricing and a Python-first SDK; E2B is the JS-friendlier choice and is the standard substrate the open-source agent community is using.
- Per-user-month cost at 1M users with realistic activity (say, 100 active minutes per user per month at minimum VM size) is in the $0.30–$1.00 range — meaningfully more than V8 isolates but in the affordable zone.
- **Verdict:** the right primary substrate for the agent's per-user persistent runtime. Snapshot-and-thaw makes the economics work; full Linux means no language lock-in; the agent can write whatever code makes sense.

### gVisor — Google Cloud Run gen2

User-space kernel intercepting syscalls. Density better than full VMs, isolation weaker than Firecracker (they've had escapes; the security boundary is software). Cloud Run gen2 is fine for stateless containers; not pitched at per-user persistent agents. Skip as primary.

### WASM — Wasmtime, Wasmer, wasmCloud, Fermyon Spin

- Capability-based sandboxing, very high density, very fast startup.
- **WASI Preview 2 is shipping; Preview 3 (async, shared state) is still landing.** Component model is real but the developer ergonomics are rough — limited library ecosystem, no JS-runtime story that's as smooth as V8.
- For the agent this is interesting in 2027–2028 if the component model matures, but in 2026 it is too early. Building on WASM today means absorbing the cost of an immature platform without a corresponding feature win that V8 or Firecracker doesn't already deliver.
- **Verdict:** watch, don't bet.

### nsjail / bubblewrap / Docker

OG sandboxes. Fine for a single host, do not scale operationally to per-user-at-1M without a substantial control plane that you'd then have to build. Don't reinvent E2B/Modal/Fly.

### Cost-at-scale ballparks (rough, not quotes)

| Substrate | Cost/user/month at 1M users (typical use) | Persistent-state story | Cold start |
|---|---|---|---|
| V8 isolate + Durable Object | ~$0.001–$0.01 | External, request-scoped compute | <5ms |
| Firecracker microVM (snapshot/thaw) | ~$0.30–$1.00 | Full memory state via snapshot | ~100–200ms |
| WASM (Wasmtime/Spin) | ~$0.005–$0.05 | Maturing | <10ms |
| Container (k8s, Docker) | ~$2–$10 | Native | seconds |

Numbers are order-of-magnitude — pricing pages move and real workloads vary 5x. The point is the *ratio*: V8 is two orders cheaper than microVMs but doesn't solve the right problem; microVMs are affordable enough at the V1 scale we care about; full containers are not.

---

## MCP as the interposition seam

The MCP (Model Context Protocol) spec defines tool servers reachable over stdio or HTTP/SSE that expose `list_tools` / `call_tool` JSON-RPC. The proxy/gateway pattern is well within spec — a proxy is just an MCP server that, when called, forwards to other MCP servers and returns synthesized results.

What this enables for the agent specifically:

- **the agent-as-MCP-server.** The user's work AI (Claude for Business, Copilot if/when it adopts MCP, an internal corporate agent) sees the agent as one of its tool servers. Tools on the agent might be `query_personal_context`, `recall_what_user_meant`, `check_against_personal_calendar`. The work AI calls these like any other tool; it never sees the personal store, only the answers.
- **the agent-as-MCP-gateway.** The user's work AI's MCP traffic to Slack/Jira/email is routed through the agent. the agent can mask results ("hide messages from the recruiter"), augment them ("the user already replied to this from their phone"), or refuse them entirely. This is the strong interposition.
- **the agent-as-client-of-corporate-MCP.** the agent itself is an agent loop that uses corporate MCP servers as tools. The user's work AI is incidental — the agent does the work directly using corporate tools, with personal context staying local.

The middle option (gateway) is the most interesting *and* the most politically fraught — the employer's IT team would have to permit a user-installed proxy in front of corporate tool traffic, which they will not by default. The first option (the agent-as-tool-server) is the most enterprise-friendly because it adds a tool rather than rerouting existing ones, but it requires the work AI to actually call the agent, which means the user has to teach it to. The third option (the agent-as-client) is the cleanest BYOD play and probably what V1 should target — it sidesteps the political fight by not being in the path of the work AI at all.

Reference implementations worth digging into next: Smithery's federation model, IBM ContextForge's "Virtual Servers" (logical bundles of tools that can be turned on/off contextually — directly relevant to the agent's "home vs work mode" concept), Red Hat Connectivity Link (mTLS + OIDC for MCP routing — production-grade plumbing if we ever go enterprise). All of these are pre-1.0 and the API surfaces will move.

---

## Recommendation for the agent V1

**Architecture:** Sidecar agent (option 3 above) using corporate APIs/MCP as tools. Don't try to interpose between the user and an existing work AI at V1. the agent is its own loop; the user talks to the agent; the agent optionally uses ChatGPT / Claude / Gemini / corporate Copilot as a *tool* when work tasks demand it.

Why: it's the only architecture where the privacy claim is true by construction. Anything that proxies the work AI either depends on the employer cooperating (won't happen at V1) or fights MDM and DLP all the way down. The sidecar-agent pattern is also the easiest demo — Sean shows the client lead a thing the user talks to that knows them well and gets work done, with nothing the employer can subpoena.

**Sandbox:** Firecracker microVMs with snapshot/thaw, hosted on E2B, Modal, or Fly.io Machines for V1. Pick whichever has the cleanest JS-runtime + persistent-volume story when we actually start building. Don't build V8-isolate plumbing — the statefulness fight isn't worth the cost saving at the scales we'll see for the first 18 months.

**MCP posture:** the agent exposes itself as an MCP server (so any MCP-aware client, including future enterprise Copilot deployments, can call into the agent's personal-context tools). the agent also acts as an MCP client of upstream tool servers when the agent needs them. We do not interpose between the user and an existing work AI at V1 — that fight comes later.

**Implication for the runtime described in CLAUDE.md:** the Datomic-style fact graph lives inside the per-user microVM (or in a per-user database the microVM connects to — Turso, Neon-per-tenant, or sqlite-on-volume). The "agent writes JS into its own sandbox" story works cleanly because we have a real Linux box per user. The "context = fn(@db, current_situation)" projection is just a function the agent runs in its own VM.

---

## Open sub-questions

- **Is the BYOD-only constraint a death sentence for enterprise revenue?** If yes, the strategic answer is to pivot to "the agent-as-tool-server," sell to IT as a productivity layer, and accept that the privacy promise is weakened (the employer chose the agent; they can theoretically subpoena it).
- **How does the agent authenticate to corporate MCP servers / SaaS APIs without holding employer-issued credentials it shouldn't have?** This is the same problem 1Password has at the policy layer. Worth reading how Pomerium / Teleport / Tailscale solve user-scoped credential brokering.
- **At what user count does per-user microVM stop being the right substrate?** Probably never for active users; the question is what to do with users who haven't talked to the agent in 60 days. Hibernate to S3, lazy-thaw on next interaction, charge nothing for storage. Modal does this well; check the actual pricing.
- **MCP spec evolution risk.** The spec is pre-1.0 and the proxy/gateway patterns are convention, not standardized. If the spec adds an explicit "no proxies" provision (unlikely but possible for security reasons), the gateway play breaks. Worth tracking the working-group meetings.
- **The "the agent-as-tool-the-work-AI-calls" play depends on the user's work AI being instructable to call the agent.** ChatGPT enterprise won't add a custom MCP server unless the employer adds it. Claude.ai will. Gemini for Workspace probably won't. The reachable surface is smaller than it looks. Map it explicitly.
- **Honest gap:** I haven't probed the realistic failure modes of snapshot/thaw at 1M users. Fly.io has had platform incidents around Machines; AWS Lambda SnapStart had an early period of cold-start regressions. Need a ground-truth read from someone running it in production at scale before betting V1 on it.
