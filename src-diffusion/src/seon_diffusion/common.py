"""Pure span/offset helpers — the living copies (extracted from the frozen
CUDA worker's diffgemma_common.py). No torch, no mlx: usable by any
worker, test, or scorer."""


def build_offset_map(tkz, code_buffer_tokens):
    """CANONICAL char-span <-> code-buffer-token map.

    Returns `(text, offset_map)` where `offset_map` is `[pos, char_start,
    char_end]` per code_buffer position (half-open char ranges over the
    cumulative per-token decode). `skip_special_tokens=False` is
    load-bearing: special tokens occupy code_buffer positions and char space.
    """
    text_parts = []
    offset_map = []
    cursor = 0
    for pos, tid in enumerate(code_buffer_tokens):
        piece = tkz.decode([tid], skip_special_tokens=False)
        cs = cursor
        ce = cursor + len(piece)
        offset_map.append([pos, cs, ce])
        text_parts.append(piece)
        cursor = ce
    return "".join(text_parts), offset_map


def span_to_positions(offset_map, span):
    """Char span [s, e) -> code_buffer token positions whose ranges OVERLAP it.

    Overlap, not containment: parser spans and BPE boundaries do not align —
    a symbol may share a piece with an adjacent paren or split across pieces.
    """
    s, e = span
    return [pos for (pos, cs, ce) in offset_map if cs < e and ce > s]
