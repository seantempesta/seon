"""Greedy decode for the MLX needle port, matching run.py's loop.

Reference semantics (reference-code/needle/needle/model/run.py):
- encoder input = [query tokens..., <tools> (id 5), tools tokens...]
  truncated to max_enc_len (query keeps max_enc_len - 2, tools fill the rest)
- decoder buffer starts [EOS]; the model emits <tool_call> first, then the
  answer tokens; generation stops at EOS or max_gen_len - 1 tokens
- leading "<tool_call>" text is stripped from the decoded output

Differences from run.py (deliberate):
- KV-cached incremental decode (mathematically identical for greedy; the
  reference re-runs the full 512-buffer decoder every step)
- no snake_case tool-name normalization (our targets are Clojure forms)
- no trie-constrained decoding yet (B2 ports it to the Clojure grammar)

Perf convention (owner): sizes and speeds ALWAYS in tokens (tok/s),
prefill and decode reported separately.
"""

import time

import mlx.core as mx

from .model import make_padding_mask
from .tokenizer import DEFAULT_MAX_ENC_LEN, DEFAULT_MAX_GEN_LEN


def build_encoder_input(tokenizer, query, tools, max_enc_len=DEFAULT_MAX_ENC_LEN):
    """[query..., <tools>, tools...] truncated exactly like run.py."""
    q_toks = tokenizer.encode(query)
    t_toks = tokenizer.encode(tools)
    max_query = max_enc_len - 2
    if len(q_toks) > max_query:
        q_toks = q_toks[:max_query]
    remaining = max_enc_len - len(q_toks) - 1
    return q_toks + [tokenizer.tools_token_id] + t_toks[:remaining]


def _strip_tool_call(text):
    if text.startswith("<tool_call>"):
        return text[len("<tool_call>"):]
    return text


def generate_batch(model, tokenizer, queries, tools_list,
                   max_gen_len=DEFAULT_MAX_GEN_LEN,
                   max_enc_len=DEFAULT_MAX_ENC_LEN):
    """Batch greedy decode. Returns a result dict (texts, tokens, tok/s)."""
    B = len(queries)
    pad_id = tokenizer.pad_token_id
    eos_id = tokenizer.eos_token_id

    enc_lists = [build_encoder_input(tokenizer, q, t, max_enc_len)
                 for q, t in zip(queries, tools_list)]
    max_enc = max(len(toks) for toks in enc_lists)
    enc = mx.full((B, max_enc), pad_id, dtype=mx.int32)
    for i, toks in enumerate(enc_lists):
        enc[i, :len(toks)] = mx.array(toks, dtype=mx.int32)

    # --- prefill: encoder pass ---
    t0 = time.perf_counter()
    src_mask = make_padding_mask(enc, pad_id)
    encoder_out, enc_mask = model.encode(enc, src_mask=src_mask)
    mx.eval(encoder_out)
    prefill_s = time.perf_counter() - t0
    prefill_tokens = sum(len(toks) for toks in enc_lists)

    # --- greedy decode, KV-cached, one token per step ---
    caches = model.new_caches()
    tokens = mx.full((B, 1), eos_id, dtype=mx.int32)
    finished = [False] * B
    gen_tokens = [[] for _ in range(B)]

    t0 = time.perf_counter()
    for pos in range(max_gen_len - 1):
        logits = model.decode(tokens, encoder_out, cross_mask=enc_mask,
                              offset=pos, caches=caches)
        next_toks = mx.argmax(logits[:, -1, :], axis=-1)
        next_list = [int(t) for t in next_toks]
        step = []
        for i in range(B):
            t = next_list[i]
            if not finished[i]:
                if t == eos_id:
                    finished[i] = True
                else:
                    gen_tokens[i].append(t)
            step.append(pad_id if finished[i] else t)
        if all(finished):
            break
        tokens = mx.array(step, dtype=mx.int32)[:, None]
    decode_s = time.perf_counter() - t0
    decode_tokens = sum(len(g) for g in gen_tokens)

    texts = [_strip_tool_call(tokenizer.decode(g)) for g in gen_tokens]
    return {
        "texts": texts,
        "tokens": gen_tokens,
        "prefill_tokens": prefill_tokens,
        "prefill_s": prefill_s,
        "prefill_tok_s": prefill_tokens / prefill_s if prefill_s > 0 else 0.0,
        "decode_tokens": decode_tokens,
        "decode_s": decode_s,
        "decode_tok_s": decode_tokens / decode_s if decode_s > 0 else 0.0,
    }


def generate(model, tokenizer, query, tools="[]",
             max_gen_len=DEFAULT_MAX_GEN_LEN, max_enc_len=DEFAULT_MAX_ENC_LEN):
    """Single-example greedy decode. Returns a result dict (text, tok/s)."""
    r = generate_batch(model, tokenizer, [query], [tools],
                       max_gen_len=max_gen_len, max_enc_len=max_enc_len)
    r["text"] = r["texts"][0]
    return r


README_EXAMPLES = [
    ("What is the weather in San Francisco?",
     '[{"name": "get_weather", "description": "Get current weather for a city.", '
     '"parameters": {"location": {"type": "string", "description": "City name.", "required": true}}}]'),
    ("Send an email to john@example.com saying hello",
     '[{"name": "send_email", "description": "Send an email to a recipient.", '
     '"parameters": {"to": {"type": "string", "description": "The recipient email address.", "required": true}, '
     '"body": {"type": "string", "description": "The email body text.", "required": true}}}]'),
    ("Get the current stock price of AAPL",
     '[{"name": "get_stock_price", "description": "Get the current stock price.", '
     '"parameters": {"symbol": {"type": "string", "description": "Ticker symbol.", "required": true}}}]'),
]


def main():
    from .model import load_model
    from .tokenizer import load_tokenizer

    model = load_model()
    tokenizer = load_tokenizer()

    for q, t in README_EXAMPLES:
        r = generate(model, tokenizer, q, tools=t)
        print(f"\nquery:  {q}")
        print(f"output: {r['text']}")
        print(f"  prefill {r['prefill_tokens']} tok in {r['prefill_s']*1e3:.1f} ms "
              f"({r['prefill_tok_s']:.0f} tok/s) | "
              f"decode {r['decode_tokens']} tok in {r['decode_s']*1e3:.1f} ms "
              f"({r['decode_tok_s']:.0f} tok/s)")

    # batch throughput over the same three
    qs = [q for q, _ in README_EXAMPLES]
    ts = [t for _, t in README_EXAMPLES]
    r = generate_batch(model, tokenizer, qs, ts)
    print(f"\nbatch of {len(qs)}: prefill {r['prefill_tok_s']:.0f} tok/s, "
          f"decode {r['decode_tok_s']:.0f} tok/s")


if __name__ == "__main__":
    main()
