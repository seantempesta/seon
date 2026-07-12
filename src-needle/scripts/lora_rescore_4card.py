#!/usr/bin/env python3
"""Rescore KT3b-framing (4-card) prediction files under KT3-redux scoring v2.

The KT3b driver (kt3b_coder_models.py) generates + scores with the LEGACY
scorer against v1 whole-turn targets — those numbers anchor to the KT3b bars
(.265/.383). This bridge rescoderes the SAME predictions with the EXTENDED
kt3_score.clj mode (set-union best-match F1 over the v2 target_bundle,
next-form secondary, full decomposition), on the redux eval row set, so
4-card and full-index arms sit in one comparable table.

v1 preds row ids -> v2 rows via v2 meta.v1_row.

Usage:
  python3 scripts/lora_rescore_4card.py --preds data/kt3b/preds-<tag>-<arm>.jsonl \
      --tag <tag> --arm <arm>-4card
Writes kt3redux/{scored,scored-next,summary}-<tag>-<arm>-4card.json.
"""

import argparse
import json
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPTS))
import kt3_redux as KR  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preds", required=True)
    ap.add_argument("--tag", required=True)
    ap.add_argument("--arm", required=True)
    args = ap.parse_args()

    rows, index_text, extras_of, index_syms, exemplars = KR.prep()
    v1_to_v2 = {r["meta"]["v1_row"]: i for i, r in enumerate(rows)}

    done_v1 = {}
    for l in Path(args.preds).read_text().splitlines():
        d = json.loads(l)
        done_v1[d["id"]] = d
    done = {v1_to_v2[i]: d for i, d in done_v1.items() if i in v1_to_v2}

    ids = [i for i in KR.eval_ids(rows, exemplars, 0) if i in done]
    print(f"{args.tag}/{args.arm}: {len(done)} preds -> {len(ids)} scored "
          f"(redux eval set minus missing)")
    KR.OUTDIR.mkdir(parents=True, exist_ok=True)
    KR.score_and_summarize(args.tag, args.arm, f"rescored:{args.preds}",
                           rows, ids, done, index_syms, extras_of, exemplars)


if __name__ == "__main__":
    main()
