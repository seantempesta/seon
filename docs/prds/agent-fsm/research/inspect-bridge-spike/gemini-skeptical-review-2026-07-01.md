---
type: research
status: active
tags: [research, agent]
---

# Skeptical Design Review: inspect-ai <-> Seon Bridge

This document provides a critical, skeptical analysis of the proposed integration between **inspect-ai** (System A) and **Seon** (System B).

---

## Architectural Overview & The Core Tension

```mermaid
graph TD
    subgraph Host (inspect-ai)
        I[inspect-ai Task] -->|1. POST /solve| S[Seon Pod (Node/ClojureScript)]
        I -->|Bypasses| MB[Agent Bridge / Model Proxy]
        Sc[Scorer] -->|5. Graded Output| I
    end
    subgraph Docker Sandbox (per sample)
        DS[Exec Environment]
    end
    subgraph Seon Internal Loop
        S -->|2. Multi-turn Eval FSM| S
        S -->|3. LLM API Call| DSK[(DeepSeek API)]
        S -->|4. HTTP Tool Call| DS
    end
```

The core constraint—**do not modify Seon's internal agent loop/FSM**—leads to a black-box HTTP client architecture. While this preserves Seon's ClojureScript eval runtime, it creates significant friction with inspect-ai's core design assumptions.

---

## 1. Custom `@solver` via HTTP vs. Agent Bridge/Model Proxy

By treating Seon as a black box (`POST /solve -> final reply`), you bypass `inspect-ai`'s model-proxy and agent-bridge. 

### What You Lose
*   **Per-Turn Transcript & Viewer UI (Critical Dev Loss):** Inspect's viewer is a powerful debugging tool. It visualizes the prompt, completion, tool call, and tool output of *every single turn*. With your design, inspect's log will show only one giant turn: `Solver started` -> `Solver returned reply`. You will have zero visibility in the inspect UI into *why* an agent failed, where it got stuck, or what Clojure forms it evaluated. You will have to correlate inspect logs with Seon's stdout logs manually.
*   **Token Accounting & Cost Tracking:** inspect-ai automatically tracks input/output tokens and cost when routing through its model providers. Bypassing this means inspect reports `$0.00` and `0 tokens` used. To get baseline cost/token benchmarks, you must manually extract token usage from Seon's HTTP response and hack it back into the inspect state.
*   **Built-in Caching:** inspect-ai supports caching at the LLM call level. Bypassing it means that if a sample crashes on turn 15, or if you run the benchmark multiple times to tweak scorers, you pay for *every single DeepSeek API call* again.
*   **Refusal & Retry Handling:** inspect-ai has robust handling for LLM rate limits, network retries, and formatting refusals. Seon must duplicate this logic.

### Does it matter for a baseline?
Yes. While a simple pass/fail metric is technically achievable, **reproducibility and error analysis** are the primary values of inspect-ai. Running a benchmark without structured token accounting and execution traces defeats much of the purpose of using inspect-ai as an evaluation framework.

---

## 2. Benchmark Constraints & Budgets (Epochs, Timeouts, Tokens)

inspect-ai enforces budgets (e.g., `time_limit`, `max_messages`, `token_limit`) at the solver level. In a black-box model, these boundaries break.

### The Leaks and Blind Spots
*   **Orphaned Pod Processes (Resource Leaks):** If inspect-ai hits its timeout (e.g., 10 minutes) on the `@solver` task, it will raise a timeout error and proceed to the next sample. However, because the Seon pod is an independent process running on the host, **the ClojureScript FSM will keep running in the background**. It will continue querying DeepSeek, consuming your API budget, and eating CPU/RAM on the host, completely unaware that inspect has abandoned it.
*   **Token/Message Limits are Invisible:** inspect cannot enforce a token limit or message turn limit if it doesn't see the turns. A buggy agent could burn $50 of DeepSeek credits on a single sample before the outer inspect timeout kills the solver.

### Proposed Fixes
1.  **Protocol Extensions:** The `/solve` payload must specify limits, and the pod must enforce them natively:
    ```json
    {
      "input": "task description",
      "limits": {
        "max_turns": 30,
        "max_tokens": 100000,
        "timeout_ms": 300000
      }
    }
    ```
2.  **Structured Response:** The pod must return token consumption and termination metadata:
    ```json
    {
      "output": "final answer",
      "metadata": {
        "turns": 14,
        "tokens_used": 12840,
        "termination_reason": "completed" // or "timeout", "max_turns"
      }
    }
    ```

---

## 3. Tool-Bridge: Pod-on-Host vs. Pod-in-Sandbox (The Security & Performance Gap)

For GAIA-level tasks requiring a bash/file sandbox, you propose keeping the pod on the host and exposing a custom Clojure namespace (`sandbox/bash`) that HTTP round-trips back to inspect's docker exec API.

### The Vulnerability: Host Escape by Design
ClojureScript/Clojure is an extremely dynamic, reflective environment. If the agent loop runs in a Node process on the host, **restricting the agent to the `sandbox/` namespace is security theater**.
*   An agent that can write and evaluate arbitrary Clojure forms can easily import standard Node.js libraries (e.g., `(js/require "fs")` or `(js/require "child_process")`).
*   A model executing an untrusted payload (or generating code that it self-evaluates) can run arbitrary code on your host machine, bypassing the Docker sandbox entirely.

> [!CAUTION]
> If a task asks the agent to "download this file and run it," and the agent executes that step natively via the Node environment rather than your custom sandbox wrappers, **your host machine is compromised**.

### The Performance Hit
A typical GAIA or bash task involves dozens of micro-operations (e.g., checking if a file exists, running `cat`, checking stdout, modifying a line, running tests). 
*   **HTTP Roundtrips:** Pod (Host) $\rightarrow$ inspect (Host) $\rightarrow$ Docker Exec $\rightarrow$ Container VM $\rightarrow$ Host.
*   Adding 50-100ms of latency to *every* basic file system check or shell execution will make benchmark suites extremely slow.

### The Skeptical Recommendation: Run the Pod inside the Container
Instead of a tool bridge, build a Docker image that contains Node, ClojureScript, your Seon pod code, and the tool dependencies.
*   For each sample, inspect spins up the container.
*   The `@solver` starts the Seon pod *inside* that container (e.g., via `docker exec -d`).
*   The solver makes its HTTP `/solve` request to the containerized pod.
*   **Security:** If the agent executes raw Node `child_process.exec` or writes to `/tmp`, it happens inside the container.
*   **Performance:** Seon tools execute natively on the local filesystem and shell inside the container without HTTP roundtrips.

---

## 4. Anti-Cheat & Leakage Vectors

While keeping the answer key host-side in inspect-ai is standard, there are subtle leakage paths in your proposed design:

1.  **Shared Filesystem / Environment Leaks:** If the pod runs on the host, and inspect-ai writes execution logs or temp files containing the "target" (scorer keys) to the host disk (like `/tmp` or the project root), the host-based pod can read them.
2.  **Metadata Leakage in Prompt:** Ensure your custom `@solver` does not accidentally pass the `sample` object's metadata (which often includes `target` or `ideal` answers) to the `/solve` payload. It must strictly sanitize the payload to include only the task instruction.

---

## 5. State Pollution & Parallelism (The Fatal Flaw)

This is the most critical blocker for your current design.

### State Pollution (Namespace & Atom Bleed)
"The pod is long-running and shared." ClojureScript's runtime is stateful.
*   If Sample 1 defines a variable `(def x 10)` or defines a helper function, that binding **persists in memory** for Sample 2.
*   If Sample 1 crashes or pollutes a core namespace (e.g., modifying `cljs.core` behavior or overriding a sandbox function), all subsequent samples will fail or behave unpredictably.
*   This violates the core rule of benchmarking: **Samples must be independent and identically distributed (I.I.D.)**. You cannot have Sample 17's success depend on a function written during Sample 5.

### Concurrency Collisions
inspect-ai runs samples concurrently (typically 10-20 workers at once).
*   If you have **one** HTTP pod running on the host, concurrent `/solve` requests will execute in the same ClojureScript runtime.
*   They will step on each other's namespaces, write to the same temporary files, and overwrite each other's state. It will be absolute chaos.

### The Fix
You must achieve **process isolation per sample**.
*   **Option A (If running on host):** The `@solver` must spin up a fresh Node process (`node seon-pod.js`) on a dynamic port for *every single sample*, run the solver, get the result, and terminate the process.
*   **Option B (Recommended):** Use the containerized pod model. Each sample gets its own Docker container, which runs its own isolated pod. Parallelism is handled naturally by Docker, and state pollution is mathematically impossible because the container is destroyed at the end of the sample.

---

## Summary of Major Flaws & Actions

| Flaw | Severity | Impact | Action Required |
| :--- | :--- | :--- | :--- |
| **State Pollution** | 🔴 Critical | Invalidates benchmark results; memory/namespace bleed between samples. | Reset ClojureScript runtime per sample, or spin up a fresh process per worker. |
| **Concurrency Collision** | 🔴 Critical | Single shared pod cannot handle parallel inspect-ai workers. | Dynamic port allocation per sample or containerized pods. |
| **Host Escape (Security)** | 🟡 High | Clojure evals can execute arbitrary code directly on the host machine. | Run the Seon pod inside the Docker sandbox, not on the host. |
| **Orphaned Pod Processes** | 🟡 High | Terminated inspect runs leave background agents running & consuming API cost. | Implement active timeouts in the `/solve` payload and pod FSM. |
| **Zero Transcript Visibility** | 🟢 Medium | Cannot debug agent trajectories or analyze failure modes in inspect's viewer. | Log execution steps in the `/solve` response and write them back to inspect logs. |

