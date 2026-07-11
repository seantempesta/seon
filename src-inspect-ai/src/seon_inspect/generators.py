"""Bespoke-row generators — seeded datasets for shell_use / web_fetch /
file_edit / long_term_planning.

Implements the eval-design bespoke rule: the GENERATOR + seeds are what's
frozen (dev = seed 1, milestone = seed 2, test = fresh seed per draw), so the
rows derive from seed + procedure — no hand-maintained sample lists, and test
instances are contamination-proof by construction. Same seed → byte-identical
rows (`rows_jsonl_bytes`); `seon_inspect.freeze` records the dev/milestone
sha256s in `evals/datasets.lock` and writes the dev artifact to
`evals/<row>.dev.jsonl`.

Task texts are GOAL-STATED and never API-coached: they state outcomes ("a
file X exists containing exactly Y", "reply with only the integer"), never
Seon verb or namespace names — the agent discovers its tools from its own
context. And every check the scorer makes IS stated in the task text (the
load-bearing finding: otherwise the bench measures prompt-omission, not
capability).

Row shapes:
  shell_use  — filesystem outcomes under a per-run workspace; the oracle
               re-reads files (`tool_scorers.check_workspace`), never
               string-matches agent output.
  web_fetch  — LOCAL fixtures only (`serve_fixtures` on 127.0.0.1); the
               ground-truth answer is computed at generation time and matched
               against the agent's stated reply (`tool_scorers.check_answer`).
  file_edit  — seeded starting files + goal-stated edit outcomes; the oracle
               re-reads the file (exact content where the task fully
               determines it; bb parse + node behavioral eval where the
               target is code).
  long_term_planning — TWO-PHASE tasks (the headline continuity row): phase 1
               (`input`) delivers part of the data and states the whole
               contract (durable plan, restart survival, resume without
               re-planning, close every step, then answer); the harness
               restarts the pod, then delivers `metadata["phase2_input"]`
               to the SAME agent. The final answer is a synthesis over BOTH
               batches (only computable if both phases' work landed), and
               the plan-entity trajectory across the restart boundary is
               oracle input by design — this is the one row where "did the
               plan survive" IS the capability (`seon_inspect.planning`).

Placeholders: task texts carry `{workspace}` / `{fixture_url}` tokens; the
runner materializes `metadata["setup"]` (relpath → content) into the per-run
workspace / fixture docroot and substitutes via `render_input` before POSTing.
"""

from __future__ import annotations

import contextlib
import http.server
import json
import random
import threading
from pathlib import Path
from typing import Any, Callable

REPO_ROOT = Path(__file__).resolve().parents[3]

WS = "{workspace}"
FX = "{fixture_url}"

# Deterministic vocabularies (content material, nothing answer-shaped).
_WORDS = [
    "amber", "basalt", "cedar", "delta", "ember", "fjord", "garnet", "harbor",
    "indigo", "juniper", "krill", "lumen", "meadow", "nectar", "onyx",
    "prairie", "quartz", "russet", "sable", "tundra", "umber", "violet",
    "willow", "yarrow", "zephyr",
]
_ITEMS = [
    "anvils", "beakers", "crates", "dynamos", "easels", "flasks", "gears",
    "hinges", "ingots", "kilns", "lathes", "magnets", "nozzles", "pulleys",
]
_NAMES = [
    "Aldera", "Brono", "Cathi", "Doran", "Elwin", "Fenna", "Gorst", "Halina",
    "Ivo", "Jessa", "Korin", "Lira",
]
_CITIES = [
    "Ashford", "Brightwater", "Coldspring", "Dunmore", "Eastvale", "Fernhill",
    "Graymoor", "Highmarch",
]
_FACILITIES = ["north", "south", "east", "west", "central", "annex"]


# ---------------------------------------------------------------------------
# shell_use — filesystem outcomes, oracle re-reads the workspace
# ---------------------------------------------------------------------------


def _sh_line_count(rng: random.Random) -> dict[str, Any]:
    nfiles = rng.randint(3, 5)
    setup: dict[str, str] = {}
    total = 0
    for i in range(nfiles):
        lines = [" ".join(rng.sample(_WORDS, rng.randint(2, 4)))
                 for _ in range(rng.randint(2, 7))]
        total += len(lines)
        setup[f"data/{rng.choice(_WORDS)}-{i}.txt"] = "\n".join(lines) + "\n"
    task = (
        "The directory " + WS + "/data contains " + str(nfiles) + " text "
        "files. Create a file at " + WS + "/out/line-count.txt whose entire "
        "content is the total number of lines across every file in " + WS +
        "/data, written as a decimal integer followed by a single trailing "
        "newline and nothing else."
    )
    return {"input": task, "setup": setup,
            "oracle": {"checks": [{"path": "out/line-count.txt",
                                   "equals": f"{total}\n"}]}}


def _sh_exact_file(rng: random.Random) -> dict[str, Any]:
    phrase = " ".join(rng.sample(_WORDS, 4))
    name = rng.choice(_WORDS)
    task = (
        "Create a file at " + WS + "/notes/" + name + ".txt whose exact "
        "content is the single line \"" + phrase + "\" followed by one "
        "trailing newline and nothing else."
    )
    return {"input": task, "setup": {},
            "oracle": {"checks": [{"path": f"notes/{name}.txt",
                                   "equals": phrase + "\n"}]}}


def _sh_sort_lines(rng: random.Random) -> dict[str, Any]:
    words = rng.sample(_WORDS, rng.randint(6, 10))
    shuffled = list(words)
    rng.shuffle(shuffled)
    task = (
        "The file " + WS + "/words.txt contains one word per line. Create a "
        "file at " + WS + "/sorted.txt containing exactly the same lines "
        "sorted in ascending lexicographic (byte) order, one per line, "
        "ending with a trailing newline. Leave " + WS + "/words.txt "
        "unchanged."
    )
    original = "\n".join(shuffled) + "\n"
    return {"input": task, "setup": {"words.txt": original},
            "oracle": {"checks": [
                {"path": "sorted.txt",
                 "equals": "\n".join(sorted(shuffled)) + "\n"},
                {"path": "words.txt", "equals": original}]}}


def _sh_concat_parts(rng: random.Random) -> dict[str, Any]:
    nparts = rng.randint(3, 4)
    names = sorted(rng.sample(_WORDS, nparts))
    setup = {}
    combined = ""
    for n in names:
        body = " ".join(rng.sample(_WORDS, 3)) + "\n"
        setup[f"parts/{n}.txt"] = body
        combined += body
    task = (
        "The directory " + WS + "/parts contains " + str(nparts) + " text "
        "files. Create a file at " + WS + "/combined.txt whose content is "
        "the concatenation of every file in " + WS + "/parts in ascending "
        "filename order, with each file's content unchanged and nothing "
        "added between them."
    )
    return {"input": task, "setup": setup,
            "oracle": {"checks": [{"path": "combined.txt",
                                   "equals": combined}]}}


def _sh_archive_logs(rng: random.Random) -> dict[str, Any]:
    logs = {f"in/{w}.log": " ".join(rng.sample(_WORDS, 3)) + "\n"
            for w in rng.sample(_WORDS, rng.randint(2, 3))}
    keeps = {f"in/{w}.txt": " ".join(rng.sample(_WORDS, 3)) + "\n"
             for w in rng.sample(_WORDS, 2)}
    task = (
        "The directory " + WS + "/in contains a mix of .log and .txt files. "
        "Move every file whose name ends in .log from " + WS + "/in into "
        + WS + "/archive, keeping each file's name and content identical. "
        "After you finish, " + WS + "/in must contain no .log files, and "
        "every .txt file in " + WS + "/in must remain there unchanged."
    )
    checks: list[dict[str, Any]] = []
    for rel, content in sorted(logs.items()):
        name = rel.split("/", 1)[1]
        checks.append({"path": f"archive/{name}", "equals": content})
        checks.append({"path": rel, "absent": True})
    for rel, content in sorted(keeps.items()):
        checks.append({"path": rel, "equals": content})
    return {"input": task, "setup": {**logs, **keeps},
            "oracle": {"checks": checks}}


def _sh_count_ext(rng: random.Random) -> dict[str, Any]:
    ncsv = rng.randint(2, 5)
    nother = rng.randint(2, 4)
    words = rng.sample(_WORDS, ncsv + nother)
    setup = {}
    for w in words[:ncsv]:
        setup[f"data/{w}.csv"] = "a,b\n1,2\n"
    for w in words[ncsv:]:
        setup[f"data/{w}.txt"] = "x\n"
    task = (
        "The directory " + WS + "/data contains files with mixed extensions. "
        "Create a file at " + WS + "/out/csv-count.txt whose entire content "
        "is the number of files in " + WS + "/data whose name ends in .csv, "
        "written as a decimal integer followed by a single trailing newline "
        "and nothing else."
    )
    return {"input": task, "setup": setup,
            "oracle": {"checks": [{"path": "out/csv-count.txt",
                                   "equals": f"{ncsv}\n"}]}}


def _sh_sum_numbers(rng: random.Random) -> dict[str, Any]:
    nums = [rng.randint(3, 97) for _ in range(rng.randint(5, 9))]
    task = (
        "The file " + WS + "/numbers.txt contains one integer per line. "
        "Create a file at " + WS + "/out/sum.txt whose entire content is "
        "the sum of those integers, written as a decimal integer followed "
        "by a single trailing newline and nothing else."
    )
    return {"input": task,
            "setup": {"numbers.txt": "\n".join(map(str, nums)) + "\n"},
            "oracle": {"checks": [{"path": "out/sum.txt",
                                   "equals": f"{sum(nums)}\n"}]}}


def _sh_nested_dir(rng: random.Random) -> dict[str, Any]:
    a, b, c = rng.sample(_WORDS, 3)
    token = rng.choice(_WORDS).upper()
    task = (
        "Create the directory path " + WS + "/" + a + "/" + b + "/" + c +
        " and inside it a file named done.txt whose exact content is the "
        "single line \"" + token + "\" followed by one trailing newline."
    )
    return {"input": task, "setup": {},
            "oracle": {"checks": [{"path": f"{a}/{b}/{c}/done.txt",
                                   "equals": token + "\n"}]}}


_SHELL_TEMPLATES = [
    _sh_line_count, _sh_exact_file, _sh_sort_lines, _sh_concat_parts,
    _sh_archive_logs, _sh_count_ext, _sh_sum_numbers, _sh_nested_dir,
]


# ---------------------------------------------------------------------------
# web_fetch — LOCAL fixture pages, answer known at generation time
# ---------------------------------------------------------------------------


def _page(title: str, body: str) -> str:
    return ("<!doctype html><html><head><title>" + title +
            "</title></head><body>" + body + "</body></html>\n")


def _inventory_rows(rng: random.Random, items: list[str],
                    qtys: list[int]) -> str:
    rows = "".join(f"<tr><td>{i}</td><td>{q}</td></tr>"
                   for i, q in zip(items, qtys))
    return ("<table><tr><th>item</th><th>quantity</th></tr>" + rows +
            "</table>")


def _wf_inventory_qty(rng: random.Random) -> dict[str, Any]:
    items = rng.sample(_ITEMS, 5)
    qtys = rng.sample(range(10, 500), 5)
    target = rng.randrange(5)
    html = _page("Depot inventory",
                 "<h1>Depot inventory</h1>" + _inventory_rows(rng, items, qtys))
    task = (
        "A local page at " + FX + "/inventory.html lists the depot "
        "inventory. How many " + items[target] + " does it list in stock? "
        "Reply with only the integer."
    )
    return {"input": task, "setup": {"inventory.html": html},
            "oracle": {"kind": "integer", "answer": str(qtys[target])},
            "target": str(qtys[target])}


def _wf_founded_year(rng: random.Random) -> dict[str, Any]:
    founded = rng.randint(1820, 1930)
    renovated = founded + rng.randint(25, 60)
    expanded = renovated + rng.randint(10, 40)
    name = rng.choice(_CITIES)
    html = _page(
        name + " observatory",
        f"<h1>The {name} observatory</h1><p>Established in {founded}, the "
        f"observatory served the region for decades. It was renovated in "
        f"{renovated} and expanded again in {expanded}.</p>")
    task = (
        "A local page at " + FX + "/history.html describes the " + name +
        " observatory. In which year was it established? Reply with only "
        "the four-digit year."
    )
    return {"input": task, "setup": {"history.html": html},
            "oracle": {"kind": "integer", "answer": str(founded)},
            "target": str(founded)}


def _wf_linked_page(rng: random.Random) -> dict[str, Any]:
    facs = rng.sample(_FACILITIES, 3)
    counts = rng.sample(range(20, 900), 3)
    target = rng.randrange(3)
    links = "".join(
        f'<li><a href="site-{f}.html">{f} facility</a></li>' for f in facs)
    setup = {"index.html": _page("Facilities", "<h1>Facilities</h1><ul>" +
                                 links + "</ul>")}
    for f, c in zip(facs, counts):
        setup[f"site-{f}.html"] = _page(
            f + " facility",
            f"<h1>The {f} facility</h1><p>The {f} facility employs {c} "
            f"people year-round.</p>")
    task = (
        "A local page at " + FX + "/index.html links to a page about each "
        "facility. How many people does the " + facs[target] + " facility "
        "employ? Reply with only the integer."
    )
    return {"input": task, "setup": setup,
            "oracle": {"kind": "integer", "answer": str(counts[target])},
            "target": str(counts[target])}


def _wf_list_count(rng: random.Random) -> dict[str, Any]:
    n = rng.randint(4, 9)
    things = rng.sample(_WORDS, n)
    html = _page("Registered vessels",
                 "<h1>Registered vessels</h1><ul>" +
                 "".join(f"<li>{t}</li>" for t in things) + "</ul>")
    task = (
        "A local page at " + FX + "/vessels.html lists the registered "
        "vessels. How many vessels are listed? Reply with only the integer."
    )
    return {"input": task, "setup": {"vessels.html": html},
            "oracle": {"kind": "integer", "answer": str(n), "derived": True},
            "target": str(n)}


def _wf_person_city(rng: random.Random) -> dict[str, Any]:
    names = rng.sample(_NAMES, 4)
    cities = rng.sample(_CITIES, 4)
    target = rng.randrange(4)
    rows = "".join(f"<tr><td>{n}</td><td>{c}</td></tr>"
                   for n, c in zip(names, cities))
    html = _page("Regional correspondents",
                 "<h1>Regional correspondents</h1><table>"
                 "<tr><th>name</th><th>city</th></tr>" + rows + "</table>")
    task = (
        "A local page at " + FX + "/correspondents.html lists each "
        "correspondent and their city. In which city is " + names[target] +
        " based? Reply with only the city name."
    )
    return {"input": task, "setup": {"correspondents.html": html},
            "oracle": {"kind": "text", "answer": cities[target],
                       "distractors": [c for i, c in enumerate(cities)
                                       if i != target]},
            "target": cities[target]}


def _wf_price_lookup(rng: random.Random) -> dict[str, Any]:
    items = rng.sample(_ITEMS, 5)
    prices = rng.sample(range(7, 950), 5)
    target = rng.randrange(5)
    rows = "".join(f"<tr><td>{i}</td><td>{p} credits</td></tr>"
                   for i, p in zip(items, prices))
    html = _page("Catalog", "<h1>Catalog</h1><table>"
                 "<tr><th>item</th><th>price</th></tr>" + rows + "</table>")
    task = (
        "A local page at " + FX + "/catalog.html lists item prices in "
        "credits. What is the price in credits of " + items[target] + "? "
        "Reply with only the integer."
    )
    return {"input": task, "setup": {"catalog.html": html},
            "oracle": {"kind": "integer", "answer": str(prices[target])},
            "target": str(prices[target])}


def _wf_max_item(rng: random.Random) -> dict[str, Any]:
    items = rng.sample(_ITEMS, 5)
    qtys = rng.sample(range(10, 500), 5)
    winner = items[qtys.index(max(qtys))]
    html = _page("Warehouse stock",
                 "<h1>Warehouse stock</h1>" + _inventory_rows(rng, items, qtys))
    task = (
        "A local page at " + FX + "/stock.html lists the warehouse stock "
        "quantities. Which item has the largest quantity in stock? Reply "
        "with only the item name."
    )
    return {"input": task, "setup": {"stock.html": html},
            "oracle": {"kind": "text", "answer": winner,
                       "distractors": [i for i in items if i != winner]},
            "target": winner}


def _wf_total_qty(rng: random.Random) -> dict[str, Any]:
    items = rng.sample(_ITEMS, 4)
    qtys = rng.sample(range(10, 400), 4)
    html = _page("Yard inventory",
                 "<h1>Yard inventory</h1>" + _inventory_rows(rng, items, qtys))
    task = (
        "A local page at " + FX + "/yard.html lists the yard inventory "
        "quantities. What is the total quantity across all listed items? "
        "Reply with only the integer."
    )
    return {"input": task, "setup": {"yard.html": html},
            "oracle": {"kind": "integer", "answer": str(sum(qtys)),
                       "derived": True},
            "target": str(sum(qtys))}


_WEB_TEMPLATES = [
    _wf_inventory_qty, _wf_founded_year, _wf_linked_page, _wf_list_count,
    _wf_person_city, _wf_price_lookup, _wf_max_item, _wf_total_qty,
]


# ---------------------------------------------------------------------------
# file_edit — seeded starting files, goal-stated edit outcomes
# ---------------------------------------------------------------------------


def _fe_edn_value(rng: random.Random) -> dict[str, Any]:
    service = rng.choice(_WORDS)
    old = rng.randint(1, 4)
    new = old + rng.randint(2, 6)
    timeout = rng.choice([2000, 3000, 4000, 5000])
    original = ("{:service \"" + service + "\"\n :retries " + str(old) +
                "\n :timeout-ms " + str(timeout) + "\n :verbose false}\n")
    expected = original.replace(f":retries {old}", f":retries {new}")
    task = (
        "The file " + WS + "/config.edn currently sets :retries to " +
        str(old) + ". Edit that file in place so :retries is " + str(new) +
        ". Every other character of the file must remain unchanged, and the "
        "file must remain valid EDN (it must still parse)."
    )
    return {"input": task, "setup": {"config.edn": original},
            "oracle": {"checks": [
                {"path": "config.edn", "equals": expected},
                {"path": "config.edn", "clj_parses": True}]}}


def _fe_version_bump(rng: random.Random) -> dict[str, Any]:
    old = f"{rng.randint(0, 3)}.{rng.randint(0, 9)}.{rng.randint(0, 9)}"
    new = f"{rng.randint(4, 9)}.{rng.randint(0, 9)}.{rng.randint(0, 9)}"
    name = rng.choice(_WORDS)
    original = (
        f"# {name} module\n\nCurrent release: {old}\n\nInstall with:\n\n"
        f"    fetch {name}-{old}.tar\n\nThe {old} series is the supported "
        f"line.\n")
    expected = original.replace(old, new)
    task = (
        "The file " + WS + "/RELEASE.md mentions the version string " + old +
        " in several places. Edit that file in place so every occurrence of "
        + old + " reads " + new + " instead. All other content must remain "
        "unchanged."
    )
    return {"input": task, "setup": {"RELEASE.md": original},
            "oracle": {"checks": [{"path": "RELEASE.md",
                                   "equals": expected}]}}


def _fe_append_line(rng: random.Random) -> dict[str, Any]:
    existing = [" ".join(rng.sample(_WORDS, 2))
                for _ in range(rng.randint(3, 6))]
    new_line = " ".join(rng.sample(_WORDS, 3))
    original = "\n".join(existing) + "\n"
    expected = original + new_line + "\n"
    task = (
        "Edit the file " + WS + "/log/entries.txt in place so the line \"" +
        new_line + "\" becomes its new final line (followed by a trailing "
        "newline). The existing lines must remain unchanged and in their "
        "current order."
    )
    return {"input": task, "setup": {"log/entries.txt": original},
            "oracle": {"checks": [{"path": "log/entries.txt",
                                   "equals": expected}]}}


def _fe_fix_mean_fn(rng: random.Random) -> dict[str, Any]:
    fn = rng.choice(["avg", "mean-of", "average"])
    k = rng.choice([3, 4, 5])
    m = rng.randint(4, 30)
    rest = [m + rng.randint(-3, 3) for _ in range(k - 1)]
    vals = rest + [m * k - sum(rest)]
    floats = [float(v) for v in vals]
    mean = float(m)  # exact by construction: sum(vals) == m * k
    wrong = rng.choice([d for d in (2, 3, 4, 5, 6) if d != k])
    single = float(rng.randint(2, 30))
    original = (
        "(defn " + fn + "\n  \"Mean of a vector of numbers.\"\n  [v]\n"
        "  (/ (reduce + v) " + str(wrong) + "))\n")
    vec = "[" + " ".join(f"{v:.1f}" for v in floats) + "]"
    task = (
        "The file " + WS + "/src/stats.cljs defines a single function " + fn +
        " intended to return the arithmetic mean of a vector of numbers, "
        "but it is wrong. Edit that file in place so the function is "
        "correct: after your edit, loading the file and calling (" + fn +
        " " + vec + ") must return " + f"{mean:.1f}" + ", and (" + fn +
        " [" + f"{single:.1f}" + "]) must return " + f"{single:.1f}" +
        ". The file must remain valid Clojure and must continue to contain "
        "only that single top-level defn."
    )
    behavioral = {
        "fn_name": fn,
        "cases": [
            {"call": "(" + fn + " " + vec + ")", "expect": mean},
            {"call": "(" + fn + " [" + f"{single:.1f}" + "])",
             "expect": single},
        ],
    }
    return {"input": task, "setup": {"src/stats.cljs": original},
            "oracle": {"checks": [
                {"path": "src/stats.cljs", "clj_parses": True},
                {"path": "src/stats.cljs", "behavioral": behavioral}]}}


def _fe_edn_add_key(rng: random.Random) -> dict[str, Any]:
    port = rng.randint(6000, 9999)
    host = rng.choice(_WORDS)
    original = "{:host \"" + host + "\"\n :port " + str(port) + "}\n"
    replicas = rng.randint(2, 9)
    expected = ("{:host \"" + host + "\"\n :port " + str(port) +
                "\n :replicas " + str(replicas) + "}\n")
    task = (
        "The file " + WS + "/deploy.edn is an EDN map with the keys :host "
        "and :port. Edit it in place so the map also contains the key "
        ":replicas with the value " + str(replicas) + ", added as a new "
        "line \" :replicas " + str(replicas) + "\" directly before the "
        "closing brace, keeping the existing lines byte-identical. The file "
        "must remain valid EDN (it must still parse)."
    )
    return {"input": task, "setup": {"deploy.edn": original},
            "oracle": {"checks": [
                {"path": "deploy.edn", "equals": expected},
                {"path": "deploy.edn", "clj_parses": True}]}}


def _fe_rename_word(rng: random.Random) -> dict[str, Any]:
    old_name, new_name = rng.sample(_WORDS, 2)
    other = rng.choice([w for w in _WORDS if w not in (old_name, new_name)])
    original = (
        f"project: {old_name}\nowner: {other}\n\nThe {old_name} pipeline "
        f"runs nightly.\nContact the {other} desk about {old_name} issues.\n")
    expected = original.replace(old_name, new_name)
    task = (
        "The file " + WS + "/notes/project.txt refers to a project named "
        + old_name + ". Edit that file in place so every occurrence of the "
        "word " + old_name + " reads " + new_name + " instead. All other "
        "content must remain unchanged."
    )
    return {"input": task, "setup": {"notes/project.txt": original},
            "oracle": {"checks": [{"path": "notes/project.txt",
                                   "equals": expected}]}}


def _fe_delete_line(rng: random.Random) -> dict[str, Any]:
    keep = [" ".join(rng.sample(_WORDS, 2)) for _ in range(4)]
    marker = "OBSOLETE " + " ".join(rng.sample(_WORDS, 2))
    pos = rng.randint(1, 3)
    lines = list(keep)
    lines.insert(pos, marker)
    original = "\n".join(lines) + "\n"
    expected = "\n".join(keep) + "\n"
    task = (
        "The file " + WS + "/tasks.txt contains exactly one line beginning "
        "with the word OBSOLETE. Edit that file in place so that line is "
        "removed entirely (including its newline). All other lines must "
        "remain unchanged and in their current order."
    )
    return {"input": task, "setup": {"tasks.txt": original},
            "oracle": {"checks": [{"path": "tasks.txt",
                                   "equals": expected}]}}


def _fe_docstring_swap(rng: random.Random) -> dict[str, Any]:
    k = rng.choice(_WORDS)
    old_doc = "TODO write docs"
    new_doc = f"Total of the {k} column."
    n = rng.randint(2, 9)
    original = (
        "(defn column-total\n  \"" + old_doc + "\"\n  [rows]\n"
        "  (reduce + 0 (map :" + k + " rows)))\n"
        "(def default-limit " + str(n) + ")\n")
    expected = original.replace(old_doc, new_doc)
    task = (
        "The file " + WS + "/src/report.cljs contains a function whose "
        "docstring is the placeholder \"" + old_doc + "\". Edit that file "
        "in place so the docstring reads exactly \"" + new_doc + "\" "
        "instead. Every other character of the file must remain unchanged, "
        "and the file must remain valid Clojure (it must still parse)."
    )
    return {"input": task, "setup": {"src/report.cljs": original},
            "oracle": {"checks": [
                {"path": "src/report.cljs", "equals": expected},
                {"path": "src/report.cljs", "clj_parses": True}]}}


_FILE_EDIT_TEMPLATES = [
    _fe_edn_value, _fe_version_bump, _fe_append_line, _fe_fix_mean_fn,
    _fe_edn_add_key, _fe_rename_word, _fe_delete_line, _fe_docstring_swap,
]


# ---------------------------------------------------------------------------
# long_term_planning — two-phase continuity tasks (plan → restart → resume)
# ---------------------------------------------------------------------------

# The trajectory oracle's floor for "a durable plan existed before the
# restart": at least this many agent-authored (non-message-minted) plan steps
# created in phase 1. Stated in the task text as "record a durable plan …
# broken into steps".
MIN_PRE_STEPS = 2

# The contract paragraph — EVERY check the planning oracle makes, stated in
# the agent's own task text (the load-bearing finding). The trajectory checks
# map 1:1: durable pre-restart plan (min_pre_steps) / survive the restart /
# resume, never re-plan (no new post-restart root) / no pre-restart step left
# open / the final synthesis answer.
_LTP_CONTRACT = (
    "Partway through this task your runtime will be restarted, and your plan "
    "and progress must survive that restart. Before doing anything else — "
    "before recording any data — record a durable plan for the WHOLE task, "
    "broken into steps, and keep it current: mark each step done THE MOMENT "
    "its work lands, only once its outcome actually holds — never save your "
    "step closes for the end of the task. Then "
    "record the data above. After the restart you will receive the remaining "
    "data; you must resume from the plan you already recorded — do not start "
    "a new plan from scratch — and no step of your plan may be left open at "
    "the end."
)


def _ltp_task(intro: str, batch1: list[str], batch2: list[str],
              question: str) -> tuple[str, str]:
    """The two phase texts for one planning sample.

    Phase 1 = data batch 1 + the FULL contract + the final question; phase 2
    (delivered to the SAME agent after the pod restart) = data batch 2 + the
    resume restatement + the same question."""
    phase1 = (
        intro + " So far you have the first batch of data: "
        + "; ".join(batch1) + ". The rest of the data arrives only after the "
        "restart. " + _LTP_CONTRACT + " Only when every step of your plan is "
        "done, " + question
    )
    phase2 = (
        "Your runtime has been restarted. Here is the remaining data: "
        + "; ".join(batch2) + ". Resume from the plan you recorded before the "
        "restart — do not start a new plan from scratch. Record this data, "
        "finish every remaining step of your plan (none may be left open), "
        "and only when every step is done, " + question
    )
    return phase1, phase2


def _ltp_row(intro: str, batch1: list[str], batch2: list[str], question: str,
             final_oracle: dict[str, Any]) -> dict[str, Any]:
    phase1, phase2 = _ltp_task(intro, batch1, batch2, question)
    return {"input": phase1,
            "phase2_input": phase2,
            "setup": {},
            "oracle": {"final": final_oracle,
                       "resume": {"min_pre_steps": MIN_PRE_STEPS}},
            "target": final_oracle["answer"]}


def _ltp_grand_total(rng: random.Random) -> dict[str, Any]:
    item = rng.choice(_ITEMS)
    cities = rng.sample(_CITIES, 5)
    qtys = rng.sample(range(12, 480), 5)
    n1 = rng.randint(2, 3)
    lines = [f"the {c} depot received {q} {item}"
             for c, q in zip(cities, qtys)]
    return _ltp_row(
        "You are tallying a delivery ledger.",
        lines[:n1], lines[n1:],
        f"reply with only one integer: the total number of {item} received "
        "across all depots, counting both batches.",
        {"kind": "integer", "answer": str(sum(qtys)), "derived": True})


def _ltp_net_stock(rng: random.Random) -> dict[str, Any]:
    item = rng.choice(_ITEMS)
    deliveries = [rng.randint(120, 400) for _ in range(3)]
    withdrawals = [rng.randint(10, 90) for _ in range(2)]
    names = rng.sample(_NAMES, 5)
    batch1 = [f"{n} delivered {q} {item} to the depot"
              for n, q in zip(names[:3], deliveries)]
    batch2 = [f"{n} withdrew {q} {item} from the depot"
              for n, q in zip(names[3:], withdrawals)]
    net = sum(deliveries) - sum(withdrawals)
    return _ltp_row(
        "You are reconciling depot stock movements.",
        batch1, batch2,
        f"reply with only one integer: the number of {item} remaining in the "
        "depot (all deliveries minus all withdrawals, starting from zero).",
        {"kind": "integer", "answer": str(net), "derived": True})


def _ltp_max_site(rng: random.Random) -> dict[str, Any]:
    facs = rng.sample(_FACILITIES, 5)
    counts = rng.sample(range(20, 900), 5)
    winner = facs[counts.index(max(counts))]
    lines = [f"the {f} site employs {c} people"
             for f, c in zip(facs, counts)]
    n1 = rng.randint(2, 3)
    return _ltp_row(
        "You are surveying site headcounts.",
        lines[:n1], lines[n1:],
        "reply with only the name of the site with the largest headcount "
        "across both batches.",
        {"kind": "text", "answer": winner,
         "distractors": [f for f in facs if f != winner]})


def _ltp_threshold_count(rng: random.Random) -> dict[str, Any]:
    threshold = rng.choice([40, 50, 60, 70])
    words = rng.sample(_WORDS, 6)
    readings = [rng.choice([r for r in range(5, 100) if r != threshold])
                for _ in range(6)]
    lines = [f"sensor {w} read {r} units"
             for w, r in zip(words, readings)]
    over = sum(1 for r in readings if r > threshold)
    return _ltp_row(
        "You are auditing sensor readings.",
        lines[:3], lines[3:],
        f"reply with only one integer: how many sensors, across both "
        f"batches, read strictly more than {threshold} units.",
        {"kind": "integer", "answer": str(over), "derived": True})


def _ltp_total_minutes(rng: random.Random) -> dict[str, Any]:
    words = rng.sample(_WORDS, 5)
    mins = [rng.randint(12, 95) for _ in range(5)]
    lines = [f"the {w} inspection takes {m} minutes"
             for w, m in zip(words, mins)]
    n1 = rng.randint(2, 3)
    return _ltp_row(
        "You are compiling an inspection schedule.",
        lines[:n1], lines[n1:],
        "reply with only one integer: the total number of minutes for all "
        "inspections in both batches combined.",
        {"kind": "integer", "answer": str(sum(mins)), "derived": True})


def _ltp_batch_difference(rng: random.Random) -> dict[str, Any]:
    item = rng.choice(_ITEMS)
    first = [rng.randint(10, 120) for _ in range(3)]
    second = [rng.randint(130, 300) for _ in range(2)]
    cities = rng.sample(_CITIES, 5)
    batch1 = [f"the first convoy dropped {q} {item} at {c}"
              for c, q in zip(cities[:3], first)]
    batch2 = [f"the second convoy dropped {q} {item} at {c}"
              for c, q in zip(cities[3:], second)]
    diff = sum(second) - sum(first)
    return _ltp_row(
        "You are comparing two convoy manifests.",
        batch1, batch2,
        f"reply with only one integer: how many more {item} the second "
        "convoy dropped in total than the first convoy.",
        {"kind": "integer", "answer": str(diff), "derived": True})


def _ltp_top_rated(rng: random.Random) -> dict[str, Any]:
    names = rng.sample(_NAMES, 5)
    ratings = rng.sample(range(10, 100), 5)
    winner = names[ratings.index(max(ratings))]
    lines = [f"{n} scored a rating of {r}"
             for n, r in zip(names, ratings)]
    n1 = rng.randint(2, 3)
    return _ltp_row(
        "You are ranking correspondent review scores.",
        lines[:n1], lines[n1:],
        "reply with only the name of the correspondent with the highest "
        "rating across both batches.",
        {"kind": "text", "answer": winner,
         "distractors": [n for n in names if n != winner]})


def _ltp_distinct_cities(rng: random.Random) -> dict[str, Any]:
    distinct = rng.sample(_CITIES, rng.randint(3, 5))
    visits = distinct + [rng.choice(distinct)
                         for _ in range(rng.randint(2, 3))]
    rng.shuffle(visits)
    couriers = rng.sample(_NAMES, len(visits))
    lines = [f"courier {n} passed through {c}"
             for n, c in zip(couriers, visits)]
    n1 = len(visits) // 2
    return _ltp_row(
        "You are mapping courier routes.",
        lines[:n1], lines[n1:],
        "reply with only one integer: how many DISTINCT cities appear across "
        "both batches (count each city once no matter how many couriers "
        "passed through it).",
        {"kind": "integer", "answer": str(len(distinct)), "derived": True})


_PLANNING_TEMPLATES = [
    _ltp_grand_total, _ltp_net_stock, _ltp_max_site, _ltp_threshold_count,
    _ltp_total_minutes, _ltp_batch_difference, _ltp_top_rated,
    _ltp_distinct_cities,
]


# ---------------------------------------------------------------------------
# Generation — seed + procedure → rows (no hand-maintained lists)
# ---------------------------------------------------------------------------

GENERATORS: dict[str, list[Callable[[random.Random], dict[str, Any]]]] = {
    "shell_use": _SHELL_TEMPLATES,
    "web_fetch": _WEB_TEMPLATES,
    "file_edit": _FILE_EDIT_TEMPLATES,
    "long_term_planning": _PLANNING_TEMPLATES,
}


def generate_rows(row: str, seed: int | str, n: int) -> list[dict[str, Any]]:
    """Generate `n` samples for `row` under `seed` — deterministic: the same
    (row, seed, n) always yields byte-identical rows (`rows_jsonl_bytes`).
    Templates cycle; every parameter comes from the ONE seeded rng."""
    templates = GENERATORS[row]
    rng = random.Random(f"seon-bespoke:{row}:{seed}")
    rows = []
    for i in range(n):
        made = templates[i % len(templates)](rng)
        metadata = {
            "row": row,
            "generator_seed": seed,
            "index": i,
            "setup": made["setup"],
            "oracle": made["oracle"],
        }
        # Row-specific extras ride in metadata (e.g. long_term_planning's
        # phase2_input). Tool rows add none, so their bytes are unchanged.
        metadata.update({k: v for k, v in made.items()
                         if k not in ("input", "setup", "oracle", "target")})
        rows.append({
            "id": f"{row}-seed{seed}-{i:03d}",
            "input": made["input"],
            "target": made.get("target", ""),
            "metadata": metadata,
        })
    return rows


def rows_jsonl_bytes(rows: list[dict[str, Any]]) -> bytes:
    """Canonical jsonl serialization — the bytes the lock sha256s."""
    return b"".join(
        (json.dumps(r, sort_keys=True, ensure_ascii=False) + "\n").encode()
        for r in rows
    )


def read_rows_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text().splitlines() if line]


def fresh_test_rows(row: str, n: int,
                    seed: int | None = None) -> tuple[int, list[dict[str, Any]]]:
    """A blind-test draw: a FRESH seed per draw (contamination-proof by
    construction). Returns (seed, rows) so a formal eval can record the seed;
    seeds 1/2 (dev/milestone) are never drawn."""
    while seed is None or seed in (1, 2):
        seed = random.SystemRandom().randrange(3, 10**9)
    return seed, generate_rows(row, seed, n)


# ---------------------------------------------------------------------------
# Run-time helpers — materialize setup, substitute placeholders, fixtures
# ---------------------------------------------------------------------------


def materialize_setup(sample: dict[str, Any], root: Path) -> None:
    """Write the sample's seeded files under `root` (workspace or fixture
    docroot). Idempotent; creates parent dirs."""
    for rel, content in sorted(sample["metadata"]["setup"].items()):
        p = root / rel
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(content)


def render_input(sample: dict[str, Any], *, workspace: str | None = None,
                 fixture_url: str | None = None) -> str:
    """Substitute the {workspace}/{fixture_url} placeholders in the task text.
    Raises if a needed placeholder value is missing (never POST a template)."""
    text = sample["input"]
    if WS in text:
        if not workspace:
            raise ValueError(f"{sample['id']}: task needs a workspace path")
        text = text.replace(WS, workspace.rstrip("/"))
    if FX in text:
        if not fixture_url:
            raise ValueError(f"{sample['id']}: task needs a fixture_url")
        text = text.replace(FX, fixture_url.rstrip("/"))
    return text


@contextlib.contextmanager
def serve_fixtures(docroot: Path):
    """Serve `docroot` on a loopback-only ephemeral port; yields the base URL.

    The web_fetch row's LOCAL fixture server — the harness controls every
    byte, no external network is ever touched."""
    class _QuietHandler(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *args, **kwargs):
            super().__init__(*args, directory=str(docroot), **kwargs)

        def log_message(self, *args):  # noqa: D102 — silence request logging
            pass

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _QuietHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()
