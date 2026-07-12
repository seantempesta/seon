"""KT1 tokenizer-envelope measurement (design.md §Measurement).

Tokenizes the A1-exported REAL rows (data/tune/<store>-<date>.jsonl:
{"context","cards","target","meta"}) through the stock needle
SentencePiece model. Tokenizer ONLY — no model weights load, single
process, tiny memory.

Per row:
  enc_ctx     = tokens(context) alone
  enc_total   = enc_ctx + 1 (<tools> separator) + tokens(cards joined
                by "\n") — the run.py/build_encoder_input layout
                (query + sep + tools), measured UNTRUNCATED so overflow
                of the 1024 envelope is visible
  enc_compact = same, with each card COMPACTED to name + docstring
                line-1 + param names (the {:malli/schema ...} metadata
                map stripped from the compact-fn-head card, leaving
                `(defn name "doc" [arglist] ...)`)
  dec_target  = tokens(target); the 512 decoder envelope (training adds
                one EOS on top — reported counts exclude it)
  byte-fallback fraction (sp.IsByte) over target tokens and context
                tokens, pooled + per-row
  chars/token for contexts and targets separately (B1 anchor: 2.45)

Kill thresholds (design.md): >~25% of full encoder inputs over 1024,
OR median target > 512, OR target byte-fallback >~35%.

Run (from src-needle/):
  .venv/bin/python -m seon_needle.kt1_envelope [path/to/rows.jsonl]
Results land under data/kt1/ as JSON (gitignored; the research file
quotes them). Sizes/speeds in TOKENS, always.
"""

import json
import sys

from . import config
from .tokenizer import DEFAULT_MAX_DEC_LEN, DEFAULT_MAX_ENC_LEN, load_tokenizer

REPO_ROOT = config.repo_root()
PKG_ROOT = config.package_root()
OUT_DIR = PKG_ROOT / "data" / "kt1"
DEFAULT_ROWS = REPO_ROOT / "data" / "tune" / "acme-2026-07-12.jsonl"


def compact_card(card):
    """Strip the {:malli/schema ...} metadata map from a compact-fn-head
    card, leaving `(defn name "doc line 1" [arglist] ...)` — name +
    docstring line-1 + param names (the arglist) only. String-aware
    balanced-brace scan; a card without the metadata map passes through
    unchanged."""
    marker = " {:malli/schema "
    start = card.find(marker)
    if start < 0:
        return card
    i, depth, in_str = start + 1, 0, False
    while i < len(card):
        ch = card[i]
        if in_str:
            if ch == "\\":
                i += 1
            elif ch == '"':
                in_str = False
        elif ch == '"':
            in_str = True
        elif ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return card[:start] + card[i + 1:]
        i += 1
    return card  # unbalanced (shouldn't happen) — keep the original


def dist(xs):
    """[min p25 p50 p75 max] by nearest-rank over a non-empty list."""
    s = sorted(xs)
    n = len(s)

    def q(p):
        return s[min(n - 1, int(round(p * (n - 1))))]

    return [s[0], q(0.25), q(0.50), q(0.75), s[-1]]


def main():
    rows_path = sys.argv[1] if len(sys.argv) > 1 else str(DEFAULT_ROWS)
    tok = load_tokenizer()
    sp = tok.sp
    is_byte = [sp.IsByte(i) for i in range(sp.GetPieceSize())]

    rows = [json.loads(l) for l in open(rows_path) if l.strip()]

    enc_ctx, enc_total, enc_compact, dec_target = [], [], [], []
    ctx_bf_row, tgt_bf_row = [], []
    ctx_tok_sum = ctx_char_sum = tgt_tok_sum = tgt_char_sum = 0
    ctx_byte_sum = tgt_byte_sum = 0
    cards_tok, cards_compact_tok = [], []

    for r in rows:
        c_ids = tok.encode(r["context"])
        t_ids = tok.encode(r["target"])
        cards_text = "\n".join(r["cards"])
        compact_text = "\n".join(compact_card(c) for c in r["cards"])
        k_ids = tok.encode(cards_text) if cards_text else []
        kc_ids = tok.encode(compact_text) if compact_text else []

        enc_ctx.append(len(c_ids))
        enc_total.append(len(c_ids) + 1 + len(k_ids))  # +1 = <tools> sep
        enc_compact.append(len(c_ids) + 1 + len(kc_ids))
        dec_target.append(len(t_ids))
        cards_tok.append(len(k_ids))
        cards_compact_tok.append(len(kc_ids))

        c_bytes = sum(1 for i in c_ids if is_byte[i])
        t_bytes = sum(1 for i in t_ids if is_byte[i])
        ctx_bf_row.append(c_bytes / max(len(c_ids), 1))
        tgt_bf_row.append(t_bytes / max(len(t_ids), 1))
        ctx_byte_sum += c_bytes
        tgt_byte_sum += t_bytes
        ctx_tok_sum += len(c_ids)
        tgt_tok_sum += len(t_ids)
        ctx_char_sum += len(r["context"])
        tgt_char_sum += len(r["target"])

    n = len(rows)
    over_enc = sum(1 for x in enc_total if x > DEFAULT_MAX_ENC_LEN)
    over_enc_ctx = sum(1 for x in enc_ctx if x > DEFAULT_MAX_ENC_LEN)
    over_enc_compact = sum(1 for x in enc_compact if x > DEFAULT_MAX_ENC_LEN)
    over_dec = sum(1 for x in dec_target if x > DEFAULT_MAX_DEC_LEN)

    results = {
        "rows": n,
        "rows_path": rows_path,
        "envelope": {"enc": DEFAULT_MAX_ENC_LEN, "dec": DEFAULT_MAX_DEC_LEN},
        "encoder": {
            "context_alone": {"dist": dist(enc_ctx),
                              "over_1024": over_enc_ctx,
                              "frac_over": round(over_enc_ctx / n, 4)},
            "context_sep_cards": {"dist": dist(enc_total),
                                  "over_1024": over_enc,
                                  "frac_over": round(over_enc / n, 4)},
            "context_sep_compact_cards": {
                "dist": dist(enc_compact),
                "over_1024": over_enc_compact,
                "frac_over": round(over_enc_compact / n, 4)},
            "cards_alone": {"dist": dist(cards_tok)},
            "compact_cards_alone": {"dist": dist(cards_compact_tok)},
        },
        "decoder": {
            "target": {"dist": dist(dec_target),
                       "over_512": over_dec,
                       "frac_over": round(over_dec / n, 4)},
        },
        "byte_fallback": {
            "target_pooled": round(tgt_byte_sum / max(tgt_tok_sum, 1), 4),
            "target_per_row_dist": [round(x, 4) for x in dist(tgt_bf_row)],
            "context_pooled": round(ctx_byte_sum / max(ctx_tok_sum, 1), 4),
            "context_per_row_dist": [round(x, 4) for x in dist(ctx_bf_row)],
        },
        "chars_per_token": {
            "context_pooled": round(ctx_char_sum / max(ctx_tok_sum, 1), 3),
            "target_pooled": round(tgt_char_sum / max(tgt_tok_sum, 1), 3),
            "b1_anchor": 2.45,
        },
    }

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    out = OUT_DIR / "kt1_envelope.json"
    out.write_text(json.dumps(results, indent=1))

    print(json.dumps(results, indent=1))
    print("\nverdict inputs (design.md thresholds):")
    print(f"  enc full inputs > {DEFAULT_MAX_ENC_LEN}: "
          f"{over_enc}/{n} = {over_enc / n:.1%}  (kill if >~25%)")
    print(f"  dec target median: {dist(dec_target)[2]} tokens "
          f"(kill if > {DEFAULT_MAX_DEC_LEN})")
    print(f"  target byte-fallback pooled: {tgt_byte_sum / max(tgt_tok_sum, 1):.1%} "
          f"(kill if >~35%)")
    print("wrote", out)


if __name__ == "__main__":
    main()
