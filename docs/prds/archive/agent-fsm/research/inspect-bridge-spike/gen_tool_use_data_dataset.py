"""Generator for the tool-use/data axis dataset (held-out, regeneratable).

Each sample is a concrete data task about DRIVING THE VERBS (db/transact! +
db/query, or the my.kb toolkit) over structured rows, then reporting the RESULT OF
A COMPUTATION the agent had to express against its own stored datoms. The answer
exists NOWHERE in the prompt — it is the output of a group-by/sum, a max, a count,
or a join the agent had to run. So it is distinct from memory (a stored fact read
back verbatim): here the agent must aggregate.

Four task families x 3 variants = 12 samples:
  - catsum   : store {category amount} rows, report TOTAL amount for one category
  - maxfield : store {name score} rows, report the NAME with the highest score
  - countpred: store {name status} rows, report the COUNT with a given status
  - joinsum  : store {id qty} and {id price} rows, report SUM(qty*price) for a join

Targets are unique short tokens (integers / a name) so host-side `includes` is
unambiguous.

Run:  python gen_tool_use_data_dataset.py   # rewrites tool_use_data_dataset.jsonl
"""

from __future__ import annotations

import json
import os

# --- catsum: (rows[(cat,amt)], asked-category) -> sum for that category ---
CATSUM = [
    ([("groceries", 40), ("transport", 15), ("groceries", 25),
      ("utilities", 60), ("transport", 10)], "groceries"),   # 65
    ([("books", 12), ("food", 33), ("books", 21), ("travel", 44),
      ("food", 17)], "food"),                                 # 50
    ([("parts", 8), ("labor", 55), ("parts", 19), ("parts", 13),
      ("labor", 22)], "parts"),                               # 40
]

# --- maxfield: rows[(name,score)] -> name with max score ---
MAXFIELD = [
    ([("Mara", 71), ("Nils", 88), ("Ola", 63)], "Nils"),
    ([("Priya", 45), ("Quinn", 52), ("Rhea", 91)], "Rhea"),
    ([("Said", 30), ("Tove", 77), ("Uma", 66)], "Tove"),
]

# --- countpred: rows[(name,status)], status -> count matching ---
COUNTPRED = [
    ([("a", "open"), ("b", "closed"), ("c", "open"), ("d", "open")], "open"),   # 3
    ([("e", "active"), ("f", "idle"), ("g", "active")], "active"),              # 2
    ([("h", "done"), ("i", "todo"), ("j", "todo"), ("k", "todo"), ("l", "done")], "todo"),  # 3
]

# --- joinsum: qty rows[(id,qty)] + price rows[(id,price)] -> SUM(qty*price) ---
JOINSUM = [
    ([("x", 2), ("y", 3)], [("x", 5), ("y", 4)]),   # 2*5 + 3*4 = 22
    ([("p", 4), ("q", 1)], [("p", 3), ("q", 9)]),   # 12 + 9 = 21
    ([("m", 5), ("n", 2)], [("m", 2), ("n", 8)]),   # 10 + 16 = 26
]


def _catsum(rows, cat, variant):
    total = sum(a for c, a in rows if c == cat)
    row_txt = ", ".join(f"{{category {c}, amount {a}}}" for c, a in rows)
    prompt = (
        "You are being tested on using your database/knowledge verbs to aggregate "
        "structured data. Store each of these expense records as a structured "
        f"entry: {row_txt}. Then QUERY them back and report the TOTAL amount for "
        f"the '{cat}' category as your final answer in one sentence, then complete. "
        "The total is not given — you must store the rows and compute it."
    )
    return {"id": f"catsum-{variant}", "input": prompt, "target": str(total)}


def _maxfield(rows, ans, variant):
    row_txt = ", ".join(f"{{name {n}, score {s}}}" for n, s in rows)
    prompt = (
        "You are being tested on using your database/knowledge verbs to aggregate "
        "structured data. Store each of these records as a structured entry: "
        f"{row_txt}. Then QUERY them back and report the NAME with the HIGHEST "
        "score as your final answer in one sentence, then complete. "
        "You must store the rows and compute the max yourself."
    )
    return {"id": f"maxfield-{variant}", "input": prompt, "target": ans}


def _countpred(rows, status, variant):
    cnt = sum(1 for _, s in rows if s == status)
    row_txt = ", ".join(f"{{item {n}, status {s}}}" for n, s in rows)
    prompt = (
        "You are being tested on using your database/knowledge verbs to aggregate "
        "structured data. Store each of these records as a structured entry: "
        f"{row_txt}. Then QUERY them back and report HOW MANY have status "
        f"'{status}' as your final answer in one sentence, then complete. "
        "You must store the rows and count them yourself."
    )
    return {"id": f"countpred-{variant}", "input": prompt, "target": str(cnt)}


def _joinsum(qty, price, variant):
    pmap = dict(price)
    total = sum(q * pmap[i] for i, q in qty)
    qty_txt = ", ".join(f"{{id {i}, qty {q}}}" for i, q in qty)
    price_txt = ", ".join(f"{{id {i}, price {p}}}" for i, p in price)
    prompt = (
        "You are being tested on using your database/knowledge verbs to JOIN and "
        "aggregate structured data. Store these quantity records: "
        f"{qty_txt}. And store these price records: {price_txt}. "
        "Then JOIN them on id, multiply qty by price for each id, and report the "
        "SUM of those products as your final answer in one sentence, then complete. "
        "The sum is not given — you must store, join, and compute it."
    )
    return {"id": f"joinsum-{variant}", "input": prompt, "target": str(total)}


def main() -> None:
    out = os.path.join(os.path.dirname(__file__), "tool_use_data_dataset.jsonl")
    samples = []
    for v, (rows, cat) in enumerate(CATSUM):
        samples.append(_catsum(rows, cat, v))
    for v, (rows, ans) in enumerate(MAXFIELD):
        samples.append(_maxfield(rows, ans, v))
    for v, (rows, status) in enumerate(COUNTPRED):
        samples.append(_countpred(rows, status, v))
    for v, (qty, price) in enumerate(JOINSUM):
        samples.append(_joinsum(qty, price, v))
    with open(out, "w") as f:
        for s in samples:
            f.write(json.dumps(s) + "\n")
    print(f"wrote {len(samples)} samples -> {out}")


if __name__ == "__main__":
    main()
