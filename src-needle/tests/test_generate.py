"""Encoder-input construction + checkpoint-dependent generation checks."""

import pytest

from seon_needle import config as cfg
from seon_needle.generate import README_EXAMPLES, build_encoder_input, generate
from seon_needle.tokenizer import TOOLS_ID

needs_checkpoint = pytest.mark.skipif(
    not cfg.weights_path().exists(),
    reason="needs converted checkpoint (python -m seon_needle.convert)")


class FakeTokenizer:
    tools_token_id = TOOLS_ID

    def encode(self, text):
        # one token per char, offset past the special ids
        return [10 + ord(c) % 50 for c in text]


def test_build_encoder_input_layout():
    tok = FakeTokenizer()
    toks = build_encoder_input(tok, "ab", "cd", max_enc_len=16)
    assert toks == tok.encode("ab") + [TOOLS_ID] + tok.encode("cd")


def test_build_encoder_input_truncation():
    tok = FakeTokenizer()
    toks = build_encoder_input(tok, "a" * 100, "b" * 100, max_enc_len=32)
    # query keeps max_enc_len - 2, tools fill the single remaining slot
    assert len(toks) == 32
    assert toks[30] == TOOLS_ID
    assert toks[:30] == tok.encode("a" * 30)


@needs_checkpoint
def test_generate_readme_example():
    from seon_needle.model import load_model
    from seon_needle.tokenizer import load_tokenizer

    model = load_model()
    tokenizer = load_tokenizer()
    q, t = README_EXAMPLES[0]
    r = generate(model, tokenizer, q, tools=t)
    assert '"name":"get_weather"' in r["text"]
    assert r["decode_tokens"] > 0
    assert r["prefill_tok_s"] > 0
