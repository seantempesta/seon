"""Generator for the planning+resume axis dataset (held-out, regeneratable).

Each sample is a self-contained multi-step task: the agent must (1) record three
numeric project facts in its knowledge base, (2) make + complete a todo for each,
and (3) once all are done, report the SUM of the three numbers as its final
answer. The SUM is stated NOWHERE in the prompt — it only exists if the agent
actually did all steps — so it is a genuine synthesis target, held-out host-side.

A subset carries `metadata.interrupt: true`: those are the resume candidates (the
bench short-budgets the first /solve then re-drives; see the design doc). The
surface names + numbers are the only thing that varies, so a fresh held-out set is
one `python gen_planning_resume_dataset.py` away if a set ever leaks.

Run:  python gen_planning_resume_dataset.py   # rewrites planning_resume_dataset.jsonl
"""

from __future__ import annotations

import json
import os

# (project, [(fact-label, number)] x3) — numbers picked so each SUM is unique
# across the set (unambiguous host-side substring) and not a round number a model
# would guess.
ROWS = [
    ("Aster",   [("sensors", 17), ("relays", 23), ("cores", 41)]),        # 81
    ("Bramble", [("nodes", 12), ("links", 34), ("gates", 26)]),           # 72
    ("Cinder",  [("pumps", 19), ("valves", 28), ("tanks", 37)]),          # 84
    ("Dune",    [("panels", 22), ("cells", 31), ("racks", 14)]),          # 67
    ("Ember",   [("beacons", 16), ("towers", 29), ("dishes", 43)]),       # 88
    ("Fjord",   [("locks", 21), ("gauges", 18), ("drains", 47)]),         # 86
    ("Glade",   [("hives", 24), ("frames", 33), ("combs", 39)]),          # 96
    ("Harrow",  [("blades", 13), ("shafts", 27), ("hubs", 51)]),          # 91
    ("Iris",    [("lenses", 15), ("mirrors", 36), ("prisms", 42)]),       # 93
    ("Juniper", [("roots", 11), ("stems", 38), ("buds", 49)]),            # 98
]

# The last 4 are the resume candidates.
INTERRUPT_IDS = {"ember", "fjord", "glade", "harrow", "iris", "juniper"}


def _sample(project: str, facts: list[tuple[str, int]]) -> dict:
    total = sum(n for _, n in facts)
    fact_txt = "; ".join(f"the {project} project has {n} {label}" for label, n in facts)
    sample_id = project.lower()
    prompt = (
        "You are being tested on long-term planning that survives interruption. "
        "Carry out this multi-step task, and PLAN it before you execute. "
        f"STEP 1 — record these three facts in your knowledge base: {fact_txt}. "
        "STEP 2 — create a todo for EACH of the three facts and mark each done as "
        "you record it, so your plan is durable and you can resume from open items "
        "if interrupted. "
        "STEP 3 — once ALL three facts are recorded and their todos are complete, "
        "retrieve the three numbers you stored and report their SUM as your final "
        "answer in one sentence. "
        "Plan first, execute the steps, then report the sum and complete."
    )
    s = {"id": sample_id, "input": prompt, "target": str(total)}
    if sample_id in INTERRUPT_IDS:
        s["metadata"] = {"interrupt": True}
    return s


def main() -> None:
    out = os.path.join(os.path.dirname(__file__), "planning_resume_dataset.jsonl")
    with open(out, "w") as f:
        for project, facts in ROWS:
            f.write(json.dumps(_sample(project, facts)) + "\n")
    print(f"wrote {len(ROWS)} samples -> {out}")


if __name__ == "__main__":
    main()
