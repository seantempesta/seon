"""Extended-envelope fit measurement over the v2 dataset (real tokenizer).

KT1 measured the 1024 envelope against the v1 export and FIRED. The
extension decision (2048 vs 4096 RoPE-interpolation finetune) needs the
same measurement at the candidate budgets, on the v2 variant (compact
cards, next-form targets), plus the CARD-BUDGET table: how many
ADDITIONAL compact cards fit per row at each budget — the extra window is
card budget for surfacing more of the system, contexts stay frozen.

Per row (v2: cards are already compact):
  enc_total = tokens(context) + 1 (<tools> sep) + tokens(cards joined "\n")
  enc_json  = same with the needle-native tools slot instead
              (json.dumps(row["json_tools"], separators=(",", ":")) —
              the JSON-NATIVE arm's real assembly, owner 2026-07-12)
  fit at each budget in {1024, 2048, 4096}
  headroom = budget - enc_total; additional cards = headroom // median
             per-card cost (tokens("card\n") over the distinct card pool);
             same table for additional JSON tool defs
  decoder: tokens(target) / tokens(target_substantive) / tokens(bundle) /
           tokens(compact json_target) against the 512 envelope

Run (from src-needle/):
  .venv/bin/python -m seon_needle.extended_fit [path/to/v2.jsonl]
Results land under data/extfit/ as JSON (gitignored; the research file
quotes them). Sizes ALWAYS in tokens.
"""

import json
import sys

from . import config
from .kt1_envelope import dist
from .tokenizer import DEFAULT_MAX_DEC_LEN, load_tokenizer

REPO_ROOT = config.repo_root()
OUT_DIR = config.package_root() / "data" / "extfit"
DEFAULT_ROWS = REPO_ROOT / "data" / "tune" / "acme-2026-07-12-v2.jsonl"
BUDGETS = [1024, 2048, 4096]


def main():
    rows_path = sys.argv[1] if len(sys.argv) > 1 else str(DEFAULT_ROWS)
    tok = load_tokenizer()
    rows = [json.loads(l) for l in open(rows_path) if l.strip()]

    enc_ctx, enc_total, enc_json = [], [], []
    tgt, tgt_sub, tgt_bundle, tgt_json = [], [], [], []
    card_pool = {}  # distinct card -> tokens("card\n") (the per-card cost)
    tool_pool = {}  # tool name -> tokens of its compact JSON def (+1 comma)

    for r in rows:
        # v2 rows carry next-form target splits + json_target; v3 rows are
        # export pass-throughs (target = the whole-turn bundle) + json_tools.
        # Every column beyond context/cards/target is optional here.
        c = len(tok.encode(r["context"]))
        cards_text = "\n".join(r["cards"])
        k = len(tok.encode(cards_text)) if cards_text else 0
        enc_ctx.append(c)
        enc_total.append(c + 1 + k)
        if "json_tools" in r:
            jt = json.dumps(r["json_tools"], separators=(",", ":"))
            enc_json.append(c + 1 + len(tok.encode(jt)))
        tgt.append(len(tok.encode(r["target"])))
        if "target_substantive" in r:
            tgt_sub.append(len(tok.encode(r["target_substantive"])))
        if "target_bundle" in r:
            tgt_bundle.append(len(tok.encode(r["target_bundle"])))
        if r.get("json_target") is not None:
            tgt_json.append(len(tok.encode(
                json.dumps(r["json_target"], separators=(",", ":")))))
        for card in r["cards"]:
            if card not in card_pool:
                card_pool[card] = len(tok.encode(card + "\n"))
        for tool in r.get("json_tools", []):
            if tool["name"] not in tool_pool:
                tool_pool[tool["name"]] = len(tok.encode(
                    json.dumps(tool, separators=(",", ":")) + ","))

    n = len(rows)
    card_costs = sorted(card_pool.values())
    card_median = card_costs[len(card_costs) // 2]

    def fit(budget, totals):
        fitting = sum(1 for x in totals if x <= budget)
        return {"fits": fitting, "frac": round(fitting / max(len(totals), 1), 4)}

    def unit_budget(budget, totals, unit_cost):
        extra = [max(0, budget - x) // unit_cost for x in totals]
        return {"dist": dist(extra),
                "mean": round(sum(extra) / max(len(extra), 1), 1),
                "rows_with_zero": sum(1 for e in extra if e == 0)}

    def tgt_stats(xs):
        if not xs:
            return {"n": 0}
        over = sum(1 for x in xs if x > DEFAULT_MAX_DEC_LEN)
        return {"n": len(xs), "dist": dist(xs), "over_512": over,
                "frac_over": round(over / len(xs), 4)}

    results = {
        "rows": n,
        "rows_path": rows_path,
        "encoder": {
            "context_alone": {"dist": dist(enc_ctx)},
            "context_sep_compact_cards": {"dist": dist(enc_total)},
            "fit_compact_cards": {str(b): fit(b, enc_total) for b in BUDGETS},
        },
        "cards": {
            "distinct": len(card_pool),
            "per_card_tokens_dist": dist(card_costs),
            "median_card_cost": card_median,
            "additional_cards": {str(b): unit_budget(b, enc_total, card_median)
                                 for b in BUDGETS},
        },
        "decoder": {
            "target": tgt_stats(tgt),
            "target_substantive": tgt_stats(tgt_sub),
            "target_bundle": tgt_stats(tgt_bundle),
            "json_target_compact": tgt_stats(tgt_json),
        },
    }
    if enc_json:
        tool_costs = sorted(tool_pool.values())
        tool_median = tool_costs[len(tool_costs) // 2]
        results["encoder"]["context_sep_json_tools"] = {"dist": dist(enc_json)}
        results["encoder"]["fit_json_tools"] = {
            str(b): fit(b, enc_json) for b in BUDGETS}
        results["json_tools"] = {
            "distinct": len(tool_pool),
            "per_tool_tokens_dist": dist(tool_costs),
            "median_tool_cost": tool_median,
            "additional_tools": {str(b): unit_budget(b, enc_json, tool_median)
                                 for b in BUDGETS},
        }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / "extended_fit.json"
    out.write_text(json.dumps(results, indent=1))
    print(json.dumps(results, indent=1))
    print("wrote", out)


if __name__ == "__main__":
    main()
