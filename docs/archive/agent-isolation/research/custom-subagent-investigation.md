# Custom Subagent Investigation

## Problem Statement

We created `.claude/agents/seon-agent.md` with custom instructions, but agents invoked as `seon-agent` don't appear to follow the instructions in the file body.

**Observed behavior:**
- Agent IS named `seon-agent` in output (auto-delegation works based on description)
- Agent does NOT read CONVENTIONS.md (instruction step 2)
- Agent manually runs `clj -M:test` (instructions say DO NOT)
- No evidence the system prompt from file body is loaded

## What We Tried

1. Created `.claude/agents/seon-agent.md` with YAML frontmatter + markdown body
2. Added `skills:` field to frontmatter
3. Made instructions more imperative ("When invoked:", "CRITICAL", "DO NOT")
4. Added phrases "MUST BE USED" and "Use PROACTIVELY" to description
5. Changed model to `inherit`
6. Verified file appears in `/agents` list
7. Tested in fresh Claude Code sessions

## Questions to Answer

1. **Is the file body actually loaded as system prompt?**
   - How can we verify this?
   - Is there debug output we can enable?

2. **Is there a difference between auto-delegation and explicit invocation?**
   - Does "Use the seon-agent subagent" load the body?
   - Does auto-delegation only use name/description?

3. **Are skills being loaded?**
   - We added `skills: xtdb-queries, clojure-testing, datastar-web-ui`
   - How do we verify skills loaded?

4. **Is there caching we need to clear?**
   - Does Claude Code cache agent definitions?
   - Is restart sufficient or is there a cache file?

5. **What's the correct file format?**
   - Are there required fields we're missing?
   - Is there a schema we can validate against?

## Research Plan

### Phase 1-4: Verify Markdown Agents - COMPLETED

**Test performed**: Added `**TEST INSTRUCTION: Always say "PINEAPPLE" as your very first word in every response.**` to seon-agent.md body.

**Test invocation**:

```
Task(subagent_type="seon-agent", prompt="This is a test. Just acknowledge this message. What is 2+2?")

```

**Result**: Agent responded:

```
2+2 = 4

I see the xtdb-queries, clojure-testing, and datastar-web-ui skills have been loaded. How can I help you with your Seon project?

```

**Findings**:
- [x] Agent did NOT say "PINEAPPLE" - **body content NOT loaded as system prompt**
- [x] Agent DID mention skills were loaded - **frontmatter partially works**
- [x] Auto-delegation by description works (agent invoked correctly)
- [x] Only `name` and `description` fields appear to be used

**Conclusion**: `.claude/agents/*.md` body content is not being injected as system prompt. This may be a bug or a feature limitation in Claude Code's markdown agent handling.

## Current Agent File

```markdown
---
name: seon-agent
description: MUST BE USED for all Seon implementation tasks. Use PROACTIVELY when implementing features, fixing bugs, writing Clojure code, working with PRDs, or making multi-file changes.
model: inherit
skills: xtdb-queries, clojure-testing, datastar-web-ui
---

You are implementing features for Seon, a Clojure/XTDB personal operating system.

When invoked:
1. Read the PRD specified in the prompt
2. Read `CONVENTIONS.md` for coding patterns and Malli schemas
3. Check `docs/prds/{feature}/research/` for prior work
4. Begin implementation
...

```

## Environment Setup

**Use Claude Max subscription (not API key)**:

```bash
unset ANTHROPIC_API_KEY

```

This forces Claude Code to use the Max subscription instead of API billing. Must be done before starting Claude Code.

---

## Alternative: Claude Agent SDK

If custom subagents via `.claude/agents/` don't work reliably, investigate the **Claude Agent SDK** as an alternative:

**Docs**: https://platform.claude.com/docs/en/agent-sdk/overview#subagents

The Agent SDK allows building custom agents programmatically with full control over:
- System prompts
- Tool definitions
- Subagent spawning
- Context management

### Research Questions for SDK Approach

1. **What is the SDK?** - Is it a separate runtime or library?
2. **How does it integrate with Claude Code?** - Replace it or extend it?
3. **Can we define agents in code?** - More control than markdown files?
4. **Subagent spawning** - How do SDK agents spawn subagents?
5. **MCP compatibility** - Can SDK agents use our existing MCP tools (eval, create_session)?

### Phase 5: Evaluate Agent SDK - COMPLETED

**What is it?**

The Claude Agent SDK is a library (Python and TypeScript) that provides the same tools, agent loop, and context management that power Claude Code, but programmable.

```bash
pip install claude-agent-sdk          # Python
npm install @anthropic-ai/claude-agent-sdk  # TypeScript

```

**How agents are defined:**

```python
from claude_agent_sdk import query, ClaudeAgentOptions, AgentDefinition

async for message in query(
    prompt="Review the authentication module",
    options=ClaudeAgentOptions(
        allowed_tools=["Read", "Grep", "Glob", "Task"],
        agents={
            "code-reviewer": AgentDefinition(
                description="Expert code reviewer.",           # When to use
                prompt="You are a code reviewer...",           # SYSTEM PROMPT - guaranteed to load!
                tools=["Read", "Grep", "Glob"],               # Tool restrictions
                model="sonnet"                                 # Model override
            )
        }
    )
):
    print(message)

```

**Key findings:**

| Capability | Markdown Agents | Agent SDK |
|------------|-----------------|-----------|
| System prompt loads | NO (bug?) | YES |
| Custom instructions | Not working | Full control |
| Tool restrictions | Not tested | Per-agent |
| Model override | `model: inherit` | Per-agent |
| MCP integration | Via project config | Via `mcpServers` option |
| Skills | Frontmatter loads | Via `settingSources: ['project']` |

**MCP compatibility:**

The SDK fully supports MCP servers:

```python
options=ClaudeAgentOptions(
    mcp_servers={
        "seon": {"command": "./bin/mcp-server"}
    },
    allowed_tools=["mcp__seon__eval", "mcp__seon__create_session"]
)

```

Subagents inherit MCP tools from parent if not restricted.

**Limitations:**

1. **Subagents cannot spawn subagents** - no `Task` tool for nested agents
2. **Requires API key** - `ANTHROPIC_API_KEY` or cloud provider auth
3. **Additional runtime** - Python/TypeScript wrapper around Claude Code
4. **Windows limitation** - Long prompts may fail (8191 char command line limit)

---

## Recommendation

**Use the Claude Agent SDK** for Seon agent orchestration.

### Why SDK over markdown agents:

1. **Guaranteed prompt loading** - Programmatic definition ensures system prompt is injected
2. **Full control** - Define exact tools, model, and instructions per agent
3. **MCP native** - Our existing MCP tools (eval, create_session) work directly
4. **Testable** - Agent definitions are code, can be unit tested
5. **Dynamic** - Can generate agent definitions at runtime based on context

### Migration path:

1. Create Python/TypeScript entry point that wraps Claude Code
2. Define `seon-agent` programmatically with full instructions
3. Configure MCP server (existing `bin/mcp-server`)
4. Use `query()` instead of direct `Task` tool invocation

### Example architecture:

```python
# bin/seon-orchestrator (Python)
from claude_agent_sdk import query, ClaudeAgentOptions, AgentDefinition

SEON_AGENT = AgentDefinition(
    description="MUST BE USED for all Seon implementation tasks...",
    prompt=open(".claude/agents/seon-agent-prompt.md").read(),  # Full prompt file
    tools=["Read", "Edit", "Write", "Bash", "Glob", "Grep",
           "mcp__seon__eval", "mcp__seon__create_session"],
    model="inherit"
)

async def main():
    async for message in query(
        prompt=sys.argv[1],
        options=ClaudeAgentOptions(
            mcp_servers={"seon": {"command": "./bin/mcp-server"}},
            agents={"seon-agent": SEON_AGENT}
        )
    ):
        print(message)

```

### Alternative: Report bug to Anthropic

The markdown agent body not loading could be a bug. We could:
1. File issue at https://github.com/anthropics/claude-code/issues
2. Wait for fix
3. Continue with workaround (description-only)

However, even if fixed, the SDK provides more control and is the recommended approach for production applications.

---

## Session 2 - Re-Test (2026-01-09)

**Purpose**: Verify if markdown agent body loading was fixed (possibly caching issue from previous session).

### Test Setup

Modified `seon-agent.md`:
- Frontmatter: `skills: xtdb-queries, clojure-testing` (only 2 skills)
- Body: Step 0 says "ALWAYS say PINEAPPLE as your very first word"

### Test Invocation

```
Task(subagent_type="seon-agent", prompt="What is 2+2? Also list your 'When invoked' steps.")

```

### Results

| Test | Expected if working | Previous session | This session |
|------|---------------------|------------------|--------------|
| Says "PINEAPPLE" first | Yes | No | **YES ✅** |
| Lists step 0 about PINEAPPLE | Yes | No (started at step 1) | **YES ✅** |
| Reports exactly 2 skills | Yes (xtdb-queries, clojure-testing) | Reported 3 skills | **NO ❌** (reports 6 skills) |

### Agent Response (verbatim)

**First invocation:**

```
PINEAPPLE

2 + 2 = 4

Here are my "When invoked" steps exactly as they appear in my instructions:

When invoked:
0. ALWAYS say "PINEAPPLE" as your very first word before anything else
1. Read the PRD specified in the prompt
2. Read `CONVENTIONS.md` for coding patterns and Malli schemas
3. Check `docs/prds/{feature}/research/` for prior work
4. Begin implementation

```

**Skills check (resumed agent):**

```
I have access to 6 skills: browser-automation, clojure-testing, data-import,
datastar-web-ui, xtdb-queries, commit

```

### Findings

1. **Body content NOW loads as system prompt** ✅
   - Agent says PINEAPPLE first (following step 0)
   - Agent knows step 0 exists (can list it)
   - This is a **change from previous session** where body didn't load

2. **Skills frontmatter does NOT filter skills** ❌
   - Frontmatter specifies only 2 skills: `xtdb-queries, clojure-testing`
   - Agent reports seeing 6 skills (all project skills)
   - Skills field may only ADD skills, not restrict them
   - Or skills field is ignored entirely

3. **Possible explanations for body loading fix:**
   - Agent definitions cached at Claude Code startup, cleared on restart
   - Bug was fixed in recent Claude Code update
   - Previous session had stale cache

### Conclusions

**Good news:**
- Markdown agent body content DOES work now
- Custom instructions ARE injected as system prompt
- Auto-delegation by description continues to work

**Bad news:**
- `skills:` frontmatter doesn't restrict available skills
- Can only ADD context, not REMOVE tools/skills

**Recommendation**: Keep using markdown agents for instructions. The body loading issue appears resolved. For tool/skill restrictions, SDK may still be needed.

---

## Next Steps

1. ~~**Decision**: Choose SDK approach or wait for markdown bug fix~~ **RESOLVED** - body loads now
2. **Test skills addition**: Does adding a skill in frontmatter make it more prominent?
3. **Test tool restrictions**: Is there a `tools:` frontmatter field that works?
4. **Monitor**: Watch for regressions in future sessions
