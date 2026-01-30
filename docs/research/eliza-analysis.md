# Eliza Architecture Analysis

Comparative analysis of ElizaOS for potential patterns to adopt in Seon.

## Executive Summary

ElizaOS is a TypeScript-based multi-agent framework designed primarily for social media bots and conversational AI. It provides a comprehensive plugin architecture, database abstraction layer, and orchestration system for running multiple agents. The codebase is well-structured with clear separation between the core runtime, plugin system, and persistence layer.

**Key Strengths:**
- Clean plugin architecture with dependency resolution and topological sorting
- Well-defined component types (Actions, Providers, Evaluators, Services) with clear responsibilities
- Type-safe event system with strongly-typed payloads
- Flexible database adapter pattern supporting multiple backends

**Limitations for Seon's Use Case:**
- No schema/contract system beyond TypeScript interfaces (no runtime validation like Malli)
- No bitemporal/history tracking - standard CRUD persistence
- Agent isolation is process-level, not namespace-level - agents don't "own" code
- No generative testing or property-based verification

Eliza solves a different problem: running conversational agents that respond to messages. Seon's goal is agents that write and evolve reliable code. However, several patterns are worth noting.

## Architecture Overview

### Package Structure

```
packages/
├── core/           # Runtime, types, memory, plugin system
├── server/         # Express HTTP/WebSocket API layer
├── client/         # React frontend
├── cli/            # CLI tool for project scaffolding
├── plugin-sql/     # Drizzle ORM database adapter
├── plugin-bootstrap/ # Default actions/providers
└── app/            # Tauri desktop app
```

### Core Runtime (`packages/core/src/runtime.ts`)

The `AgentRuntime` class is the central abstraction:

```typescript
export class AgentRuntime implements IAgentRuntime {
  readonly agentId: UUID;
  readonly character: Character;
  readonly actions: Action[] = [];
  readonly evaluators: Evaluator[] = [];
  readonly providers: Provider[] = [];
  readonly plugins: Plugin[] = [];
  services = new Map<ServiceTypeName, Service[]>();
  models = new Map<string, ModelHandler[]>();
  events: RuntimeEventStorage = {};
  stateCache = new Map<string, State>();

  // Each runtime has its own:
  // - Database adapter
  // - Plugin registry
  // - Event handlers
  // - Message service
}
```

**Key observation:** Each agent is a complete runtime instance. There's no shared state between agents except what's explicitly passed. This is simpler than Seon's namespace-based isolation but doesn't support collaborative code ownership.

### Multi-Agent Orchestration (`packages/core/src/elizaos.ts`)

The `ElizaOS` class manages multiple agent runtimes:

```typescript
export class ElizaOS extends EventTarget implements IElizaOS {
  private runtimes: Map<UUID, IAgentRuntime> = new Map();

  async addAgents(agents: AgentConfig[], options?: Options): Promise<UUID[]>;
  async startAgents(agentIds?: UUID[]): Promise<void>;
  async stopAgents(agentIds?: UUID[]): Promise<void>;

  // Unified messaging API
  async handleMessage(agentId: UUID, message: Memory): Promise<HandleMessageResult>;
}
```

The orchestrator provides:
- Agent lifecycle management (add, start, stop, delete)
- Unified messaging across all platforms
- Health checking
- Batch operations

**Comparison to Seon:** Seon's orchestrator is similar but adds:
- Isolated REPL per agent
- Isolated database per agent
- Observatory UI for watching progress
- Message persistence for learning

## Key Patterns Worth Noting

### 1. Plugin Architecture with Dependency Resolution

**File:** `packages/core/src/plugin.ts`

Eliza's plugin system includes topological sorting for dependency resolution:

```typescript
export interface Plugin {
  name: string;
  description: string;
  init?: (config: Record<string, string>, runtime: IAgentRuntime) => Promise<void>;
  services?: (typeof Service)[];
  actions?: Action[];
  providers?: Provider[];
  evaluators?: Evaluator[];
  events?: PluginEvents;
  routes?: Route[];
  dependencies?: string[];      // <-- Explicit dependencies
  testDependencies?: string[];  // <-- Test-only dependencies
  priority?: number;
  schema?: Record<string, unknown>;
}

// Topological sort ensures correct load order
export function resolvePluginDependencies(
  availablePlugins: Map<string, Plugin>,
  isTestMode: boolean = false
): Plugin[] {
  // DFS with cycle detection
  // Returns plugins in dependency order
}
```

**Worth adopting?** Yes, the dependency resolution pattern is clean. Seon's plugin system could benefit from explicit dependency declaration and automatic ordering.

### 2. Component Type Separation (Actions vs Providers vs Evaluators)

**File:** `packages/core/src/types/components.ts`

Eliza enforces clear responsibilities:

| Component | Purpose | When Called |
|-----------|---------|-------------|
| **Provider** | Supply read-only context for prompts | Before LLM call |
| **Action** | Handle user commands, execute via Services | On user input |
| **Evaluator** | Post-interaction learning and reflection | After response |
| **Service** | Stateful integrations with external APIs | Called by Actions |

```typescript
// Provider: Read-only context
interface Provider {
  name: string;
  get: (runtime: IAgentRuntime, message: Memory, state: State) => Promise<ProviderResult>;
}

// Action: User-facing capability
interface Action {
  name: string;
  description: string;
  validate: Validator;
  handler: Handler;
  examples?: ActionExample[][];
}

// Evaluator: Post-interaction analysis
interface Evaluator {
  name: string;
  alwaysRun?: boolean;
  validate: Validator;
  handler: Handler;
}
```

**Worth adopting?** Partially. The separation is useful but Seon doesn't have conversational agents. The pattern of "Providers gather context → LLM decides → Actions execute → Evaluators learn" could inform how agents gather information before making changes.

### 3. Typed Event System

**File:** `packages/core/src/types/events.ts`

Events are typed with corresponding payload types:

```typescript
export enum EventType {
  MESSAGE_RECEIVED = 'MESSAGE_RECEIVED',
  ACTION_COMPLETED = 'ACTION_COMPLETED',
  MODEL_USED = 'MODEL_USED',
  RUN_STARTED = 'RUN_STARTED',
  // ...
}

export interface EventPayloadMap {
  [EventType.MESSAGE_RECEIVED]: MessagePayload;
  [EventType.ACTION_COMPLETED]: ActionEventPayload;
  [EventType.MODEL_USED]: ModelEventPayload;
  // ...
}

// Type-safe event handler
export type EventHandler<T extends keyof EventPayloadMap> = (
  payload: EventPayloadMap[T]
) => Promise<void>;
```

Registration is type-safe:
```typescript
runtime.registerEvent(EventType.MESSAGE_RECEIVED, async (payload) => {
  // payload is typed as MessagePayload
});
```

**Worth adopting?** Yes. Seon uses protocols but doesn't have a centralized event system. A typed event bus could help with:
- Dev hook notifications
- Agent progress tracking
- Cross-component communication

### 4. Database Adapter Pattern with Domain Stores

**File:** `packages/plugin-sql/src/base.ts`

The database layer uses domain-specific stores:

```typescript
export abstract class BaseDrizzleAdapter extends DatabaseAdapter<any> {
  protected agentStore!: AgentStore;
  protected memoryStore!: MemoryStore;
  protected roomStore!: RoomStore;
  protected entityStore!: EntityStore;
  protected taskStore!: TaskStore;
  // ...

  protected initStores(): void {
    const ctx: StoreContext = {
      getDb: () => this.db,
      withRetry: (operation) => this.withRetry(operation),
      withIsolationContext: (entityId, callback) => this.withIsolationContext(entityId, callback),
      agentId: this.agentId,
    };

    this.memoryStore = new MemoryStore(ctx);
    this.taskStore = new TaskStore(ctx);
    // ...
  }
}
```

Features:
- Retry logic with exponential backoff
- Entity-level Row-Level Security (RLS) via `withIsolationContext`
- Embedding dimension management
- Migration service for schema evolution

**Worth adopting?** The RLS pattern is interesting - each database operation can run within an entity context for security. Seon could use something similar for agent isolation.

### 5. State Composition for LLM Prompts

**File:** `packages/core/src/types/state.ts`

State is composed from multiple providers for prompt context:

```typescript
interface State {
  values: { [key: string]: unknown };  // Template variables
  data: StateData;                      // Structured data cache
  text: string;                         // Concatenated context string
}

interface StateData {
  room?: Room;
  world?: World;
  entity?: Entity;
  providers?: Record<string, Record<string, unknown>>;
  actionPlan?: ActionPlan;
  actionResults?: ActionResult[];
}
```

The runtime composes state from registered providers:
```typescript
composeState(
  message: Memory,
  includeList?: string[],  // Which providers to include
  onlyInclude?: boolean,   // Exclusive or additive
  skipCache?: boolean      // Force fresh data
): Promise<State>
```

**Worth adopting?** The composability is nice. Seon could benefit from a similar pattern when agents need to understand their context (schemas available, recent changes, test results).

### 6. Service Lifecycle Management

**File:** `packages/core/src/runtime.ts` (lines 342-394)

Services are registered asynchronously with status tracking:

```typescript
private servicePromiseHandlers = new Map<string, ServicePromiseHandler>();
private servicePromises = new Map<string, Promise<Service>>();
private serviceRegistrationStatus = new Map<
  ServiceTypeName,
  'pending' | 'registering' | 'registered' | 'failed'
>();

// Wait for service to be available
getServiceLoadPromise(serviceType: ServiceTypeName): Promise<Service>;
```

This allows dependent code to wait for services to initialize.

**Worth adopting?** Yes - Seon's integrant already handles this but the async promise pattern could be useful for lazy service initialization.

### 7. Action Chaining and Multi-Step Plans

**File:** `packages/core/src/types/components.ts`

Actions can return results that chain to subsequent actions:

```typescript
interface ActionResult {
  text?: string;
  values?: Record<string, unknown>;  // Merge into state
  data?: Record<string, unknown>;    // Action-specific results
  success: boolean;
  error?: string | Error;
}

interface ActionContext {
  previousResults: ActionResult[];
  getPreviousResult?: (actionName: string) => ActionResult | undefined;
}

interface HandlerOptions {
  actionContext?: ActionContext;
  actionPlan?: {
    totalSteps: number;
    currentStep: number;
    steps: Array<{action: string; status: 'pending'|'completed'|'failed'}>;
    thought: string;  // AI's reasoning
  };
}
```

**Worth adopting?** Interesting for multi-step agent tasks. Could inform how Seon agents plan and execute changes across multiple files.

## Comparison to Seon's Approach

| Aspect | Eliza | Seon |
|--------|-------|------|
| **Primary purpose** | Conversational agents | Code-writing agents |
| **Agent isolation** | Separate runtime instances | Namespace ownership + isolated REPL/DB |
| **Schema system** | TypeScript interfaces only | Malli with runtime validation + generative testing |
| **History tracking** | None (CRUD only) | XTDB bitemporal (full history) |
| **Code ownership** | N/A - agents don't write code | Agents own namespaces long-term |
| **Contract discovery** | N/A | Schema registry with introspection |
| **Learning from history** | Evaluators after interactions | Message persistence + temporal queries |
| **Plugin architecture** | Well-developed with dependencies | Similar but less mature |
| **Testing** | TestSuite/TestCase interfaces | Kaocha + property-based testing |
| **UI** | React frontend | Datastar SSE + Terminal theme |

### What Eliza Does Better

1. **Plugin dependency resolution** - Topological sort with cycle detection
2. **Typed event system** - Clean abstraction for cross-component communication
3. **Component role clarity** - Clear separation of Providers vs Actions vs Evaluators
4. **Database abstraction** - Clean adapter pattern with domain stores

### What Seon Does Better

1. **Schema/contract system** - Malli provides runtime validation, not just types
2. **History tracking** - XTDB gives full temporal queries
3. **Agent isolation** - Each agent gets isolated REPL + DB, not just runtime
4. **Generative testing** - Property-based tests catch edge cases
5. **Code ownership model** - Agents own namespaces, can evolve code over time

## Recommendations

### Worth Adopting

1. **Explicit plugin dependencies** - Add `dependencies` and `testDependencies` to Seon plugins with topological resolution.

2. **Typed event bus** - Create a centralized event system with typed payloads for:
   - Dev hook notifications
   - Agent progress events
   - Cross-namespace communication

3. **Service status tracking** - Track service lifecycle states (`pending`, `registering`, `registered`, `failed`) for better debugging.

4. **State composition pattern** - Formal pattern for gathering context before agent decisions (schemas, recent changes, test results).

### Not Applicable

1. **Provider/Evaluator pattern** - Designed for conversational flow, not code writing
2. **Character/personality system** - Seon agents don't need personas
3. **Social media integrations** - Not relevant to code infrastructure
4. **Memory embedding search** - Seon uses XTDB SQL, not vector similarity

### Consider Carefully

1. **Action chaining** - Multi-step plans could be useful but need adaptation for file changes rather than conversation
2. **RLS isolation pattern** - Entity-context isolation is interesting but Seon already uses separate databases per agent

## Conclusion

Eliza is a mature, well-architected framework for conversational AI agents. Its plugin system and component patterns are well-designed. However, it fundamentally solves a different problem than Seon.

Eliza's agents respond to messages and maintain conversation state. Seon's agents write and evolve code, requiring:
- Schema discovery and validation (Malli)
- Full history for learning (XTDB bitemporal)
- Namespace isolation for code ownership
- Generative testing for contract verification

The patterns worth borrowing are architectural (plugin deps, typed events, service lifecycle) rather than functional (conversation handling, social integrations).

**Bottom line:** Good reference for plugin architecture and service management. Not a source for code-writing agent patterns.
