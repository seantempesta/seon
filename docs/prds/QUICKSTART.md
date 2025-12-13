# PRD Quick Start

**New to the PRD system? Start here.**

---

## Creating a New Feature

```bash
# 1. Create directory structure
mkdir -p docs/prds/your-feature-name/research

# 2. Create core files
touch docs/prds/your-feature-name/prd.md
touch docs/prds/your-feature-name/decisions.md
touch docs/prds/your-feature-name/notes.md

# 3. Or copy the example
cp -r docs/prds/_example-feature docs/prds/your-feature-name
rm docs/prds/your-feature-name/README.md  # Delete example readme
```

---

## Minimal PRD (Fast Start)

Don't need all the sections? Here's the bare minimum:

```markdown
# PRD: Your Feature Name

## Goals

1. Goal 1
2. Goal 2

## Constraints

- Constraint 1
- Constraint 2

## Success Criteria

1. Tests pass
2. Feature works
3. Documentation updated

## Deliverables

- [ ] Code
- [ ] Tests
- [ ] Docs
```

That's enough to start! Add more sections as needed.

---

## Working on an Existing Feature

```bash
# 1. Read the PRD
cat docs/prds/feature-name/prd.md

# 2. Check decisions already made
cat docs/prds/feature-name/decisions.md

# 3. Review implementation notes
cat docs/prds/feature-name/notes.md

# 4. Check research findings
ls docs/prds/feature-name/research/
```

---

## Agent Workflow (1-minute version)

1. **Exploration** - Read PRD, study resources, understand problem
2. **Research** - Test assumptions, spike solutions, document findings
3. **Implementation** - Build incrementally, test via REPL, document as you go
4. **Validation** - Run tests, verify success criteria, update docs

---

## Key Files

| File | Purpose |
|------|---------|
| `readme.md` | Full PRD system documentation (750 lines) |
| `QUICKSTART.md` | This file - fast reference |
| `_example-feature/` | Example showing structure |

---

## Most Important Rules

1. **PRDs are guidance, not gospel** - If approach doesn't work, pivot and document why
2. **Document decisions and learnings** - Future you will thank you
3. **Use REPL to verify everything** - `(integrant.repl/reset)` not `require :reload`
4. **Update as you go** - Don't wait until the end

---

## Need Help?

- **Full documentation:** `docs/prds/readme.md`
- **Example PRDs:** `docs/PRD-*.md` (old style, still good examples)
- **Project guidelines:** `CLAUDE.md` (at project root)

Happy building!
