"""BlitzLink Lite pixel-art logo (a variant of gen_logo.py, pure Python, no deps).

Same mark as BlitzLink — two chain links (the peers) bridged by a lightning bolt (the P2P link) — but the bolt is
cool "lite" cyan instead of brass, and a LITE tag sits on the plate. The banner adds LITE after BLITZLINK.
Run: python branding/gen_lite_logo.py [out_dir]   (the loader badges still come from gen_logo.py)
"""
import math, os, sys

import gen_logo as g

# cyan ramp for the Lite bolt and tag
g.PAL.update({
    'c0': (0x0E, 0x4A, 0x55),
    'c1': (0x16, 0x7A, 0x86),
    'c2': (0x2C, 0xAE, 0xBB),
    'c3': (0x6A, 0xDA, 0xE2),
    'c4': (0xD4, 0xFA, 0xFC),
})
CYAN_RAMP = ['c4', 'c3', 'c3', 'c3', 'c2', 'c2', 'c2', 'c2', 'c2', 'c1', 'c1', 'c1', 'c0', 'c0']
g.GLYPHS['E'] = ["XXXXXX", "XX....", "XX....", "XXXXX.", "XX....", "XX....", "XXXXXX"]

# 3x5 caps for the icon's tag
TINY = {
    'L': ["X..", "X..", "X..", "X..", "XXX"],
    'I': ["XXX", ".X.", ".X.", ".X.", "XXX"],
    'T': ["XXX", ".X.", ".X.", ".X.", ".X."],
    'E': ["XXX", "X..", "XX.", "X..", "XXX"],
}


def shade_bolt_cyan(mask):
    """gen_logo.shade_bolt, re-coloured brass → cyan."""
    swap = {'b0': 'c0', 'b1': 'c1', 'b2': 'c2', 'b3': 'c3', 'b4': 'c4'}
    return {p: swap.get(k, k) for p, k in g.shade_bolt(mask).items()}


def spark(cx, cy):
    return {(cx, cy): 'c4', (cx - 1, cy): 'c2', (cx + 1, cy): 'c2', (cx, cy - 1): 'c2', (cx, cy + 1): 'c2'}


def draw_mark(cv, ox=0, oy=0, sparks=True):
    ang = -math.pi / 4
    A = g.capsule_ring(10 + ox, 22 + oy, 1.6, ang, 5.8, 2.9)
    B = g.capsule_ring(22 + ox, 10 + oy, 1.6, ang, 5.8, 2.9)
    cv.layer(A, 'A')
    cv.layer(B, 'B')
    cv.layer(shade_bolt_cyan(g.bolt_mask(9 + ox, 6 + oy)), 'bolt')
    if sparks:
        cv.layer(spark(8 + ox, 9 + oy), 'sp1', outline=None)


def lite_tag(cv, x0, y0):
    """A cyan plate with dark LITE (3x5 caps), outlined; (x0, y0) is its top-left corner."""
    text_w = sum(len(TINY[c][0]) for c in 'LITE') + 3
    w, h = text_w + 4, 5 + 4
    box = {}
    for y in range(h):
        for x in range(w):
            edge = x in (0, w - 1) or y in (0, h - 1)
            box[(x0 + x, y0 + y)] = 'K' if edge else ('c3' if y == 1 else 'c1' if y == h - 2 else 'c2')
    cv.layer(box, 'tag', outline=None)
    x = x0 + 2
    pix = {}
    for ch in 'LITE':
        for gy, row in enumerate(TINY[ch]):
            for gx, c in enumerate(row):
                if c == 'X':
                    pix[(x + gx, y0 + 2 + gy)] = 'K'
        x += len(TINY[ch][0]) + 1
    cv.layer(pix, 'tagtext', outline=None)
    return w


def icon():
    cv = g.Canvas(32, 32)
    g.plate(cv)
    draw_mark(cv, oy=-1)
    lite_tag(cv, 10, 20)
    g.finish(cv)
    return cv


def banner():
    tw = g.text_width('BLITZ') + 4 + g.text_width('LINK') + 6
    lw = g.text_width('LITE') + 6
    W = 32 + 4 + tw + 6 + lw + 8
    cv = g.Canvas(W, 32)
    g.plate(cv)
    draw_mark(cv, sparks=True)
    for y in range(6, 26):
        cv.col[y][34] = 'ln'
    x = 39
    x = g.word(cv, 'BLITZ', x, 8, g.BRASS_RAMP, 'w1')
    x = g.word(cv, 'LINK', x + 4, 8, g.STEEL_RAMP, 'w2', accent=('s1', 's0'))
    g.word(cv, 'LITE', x + 10, 8, CYAN_RAMP, 'w3', accent=('c1', 'c0'))
    g.finish(cv)
    return cv


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    os.makedirs(out, exist_ok=True)
    ic = icon()
    for s in (1, 4, 16):
        ic.save(os.path.join(out, f'blitzlink-lite-icon-{32 * s}.png'), s)
    bn = banner()
    for s in (1, 4):
        bn.save(os.path.join(out, f'blitzlink-lite-banner-{s}x.png'), s)
    print('lite banner grid', bn.w, 'x', bn.h)
