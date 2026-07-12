"""Pure span/offset helpers — fake tokenizer, no model."""

from seon_diffusion.common import build_offset_map, span_to_positions


class FakeTok:
    """Each token id decodes to a fixed piece string."""

    PIECES = {1: "(defn", 2: " f", 3: " [x]", 4: " x)", 5: "<eos>"}

    def decode(self, ids, skip_special_tokens=False):
        return "".join(self.PIECES[i] for i in ids)


def test_offset_map_tiles_text():
    text, om = build_offset_map(FakeTok(), [1, 2, 3, 4, 5])
    assert text == "(defn f [x] x)<eos>"
    assert om[0] == [0, 0, 5]
    assert om[-1][2] == len(text)          # last range ends at text end
    for (p1, _, e1), (p2, s2, _) in zip(om, om[1:]):
        assert e1 == s2                    # no gaps, no overlap


def test_span_overlap_selection():
    _, om = build_offset_map(FakeTok(), [1, 2, 3, 4, 5])
    assert span_to_positions(om, [0, 5]) == [0]
    assert span_to_positions(om, [4, 8]) == [0, 1, 2]  # 8 reaches into token 2 (7-11)
    assert span_to_positions(om, [0, 19]) == [0, 1, 2, 3, 4]
    assert span_to_positions(om, [19, 25]) == []
