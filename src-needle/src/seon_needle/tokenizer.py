"""Needle tokenizer: SentencePiece BPE 8192, byte-fallback.

Special ids and interface mirror reference-code/needle/needle/dataset/
tokenizer.py (the wire contract this port must match). This file wraps
sentencepiece only — no needle code is imported at runtime.
"""

import os

import sentencepiece as spm

from . import config

PAD_ID = 0
EOS_ID = 1
BOS_ID = 2
UNK_ID = 3
TOOL_CALL_ID = 4
TOOLS_ID = 5

# Trained envelope — the binding constraint (design.md).
DEFAULT_MAX_ENC_LEN = 1024
DEFAULT_MAX_DEC_LEN = 512
DEFAULT_MAX_GEN_LEN = 512


class NeedleTokenizer:
    """SentencePiece wrapper with needle's special-token id contract."""

    def __init__(self, model_path):
        self.sp = spm.SentencePieceProcessor()
        self.sp.Load(str(model_path))

    pad_token_id = PAD_ID
    eos_token_id = EOS_ID
    bos_token_id = BOS_ID
    tool_call_token_id = TOOL_CALL_ID
    tools_token_id = TOOLS_ID

    @property
    def vocab_size(self):
        return self.sp.GetPieceSize()

    def encode(self, text):
        return self.sp.Encode(text, out_type=int)

    def decode(self, ids):
        return self.sp.Decode(list(ids))


def load_tokenizer(model_path=None):
    path = model_path or config.tokenizer_path()
    if not path or not os.path.exists(str(path)):
        raise FileNotFoundError(
            f"tokenizer not found at {path}; run `python -m seon_needle.convert` first")
    return NeedleTokenizer(path)
