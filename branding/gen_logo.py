"""BlitzLink pixel-art logo generator (pure Python, no deps).

Mark: two chain links (the peers) bridged by a brass lightning bolt (the P2P link),
on a square BlitzTheme plate. Run: python branding/gen_logo.py [out_dir]
"""
import math, struct, sys, zlib, os

# ---- palette (BlitzTheme + derived ramps) ----
PAL = {
    'bg':  (0x0B, 0x0C, 0x0A),
    'K':   (0x05, 0x06, 0x04),   # outline
    'ln':  (0x3A, 0x3F, 0x2A),   # frame line
    'ln2': (0x1A, 0x1D, 0x13),   # plate inner bevel
    # olive steel ramp (chain)
    's0':  (0x2C, 0x31, 0x14),
    's1':  (0x4B, 0x53, 0x20),
    's2':  (0x6F, 0x7D, 0x33),
    's3':  (0x9C, 0xA8, 0x5A),
    's4':  (0xC8, 0xB9, 0x8A),
    # brass ramp (bolt)
    'b0':  (0x7A, 0x52, 0x14),
    'b1':  (0xB0, 0x7E, 0x24),
    'b2':  (0xD2, 0xA0, 0x38),
    'b3':  (0xF0, 0xC8, 0x5C),
    'b4':  (0xFF, 0xF0, 0xB8),
    'r':   (0xC4, 0x2A, 0x22),
    'R':   (0xFF, 0x4A, 0x3D),
    'rd':  (0x7A, 0x18, 0x14),
    'p':   (0xE9, 0xDF, 0xC4),
    'mu':  (0x9A, 0x95, 0x81),
    'f1':  (0xA8, 0x4A, 0x18),
    'f2':  (0xE0, 0x7A, 0x2E),
    'f3':  (0xF5, 0xA4, 0x58),
}


def write_png(path, w, h, rgba_rows):
    raw = b''.join(b'\x00' + bytes(r) for r in rgba_rows)
    def chunk(t, d):
        c = struct.pack('>I', len(d)) + t + d
        return c + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
    with open(path, 'wb') as f:
        f.write(png)


class Canvas:
    def __init__(s, w, h):
        s.w, s.h = w, h
        s.col = [[None] * w for _ in range(h)]
        s.own = [[None] * w for _ in range(h)]

    def inb(s, x, y):
        return 0 <= x < s.w and 0 <= y < s.h

    def layer(s, pix, owner, outline='K'):
        """pix: dict (x,y)->palette key. Draw on top, then outline onto other owners' pixels."""
        for (x, y), c in pix.items():
            if s.inb(x, y):
                s.col[y][x] = c
                s.own[y][x] = owner
        if outline:
            for (x, y) in pix:
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if (nx, ny) in pix or not s.inb(nx, ny):
                        continue
                    o = s.own[ny][nx]
                    if o is not None and o != owner and o != 'outline':
                        s.col[ny][nx] = outline
                        s.own[ny][nx] = 'outline:' + owner

    def outline_all(s, c='K', diag=False):
        add = []
        nb = [(1, 0), (-1, 0), (0, 1), (0, -1)]
        if diag:
            nb += [(1, 1), (-1, -1), (1, -1), (-1, 1)]
        for y in range(s.h):
            for x in range(s.w):
                if s.col[y][x] is None:
                    for dx, dy in nb:
                        nx, ny = x + dx, y + dy
                        if s.inb(nx, ny) and s.col[ny][nx] is not None and s.own[ny][nx] != 'bgdeco':
                            add.append((x, y)); break
        for x, y in add:
            s.col[y][x] = c
            s.own[y][x] = 'outline'

    def rows(s, scale, bg=None):
        out = []
        for y in range(s.h):
            row = []
            for x in range(s.w):
                k = s.col[y][x] or bg
                if k is None:
                    px = [0, 0, 0, 0]
                else:
                    px = list(PAL[k] if isinstance(k, str) else k[:3]) + [255]
                row += px * scale
            for _ in range(scale):
                out.append(row)
        return out

    def save(s, path, scale=1, bg=None):
        write_png(path, s.w * scale, s.h * scale, s.rows(scale, bg))


# ---- shapes ----
def seg_dist(px, py, ax, ay, bx, by):
    vx, vy = bx - ax, by - ay
    t = max(0, min(1, ((px - ax) * vx + (py - ay) * vy) / (vx * vx + vy * vy or 1)))
    qx, qy = ax + t * vx, ay + t * vy
    return math.hypot(px - qx, py - qy), (px - qx, py - qy)


def capsule_ring(cx, cy, half, angle, r_out, r_in, light=(-1, -1)):
    """Chain link: ring around a segment. Returns dict pixel->shade key."""
    ca, sa = math.cos(angle), math.sin(angle)
    ax, ay = cx - ca * half, cy - sa * half
    bx, by = cx + ca * half, cy + sa * half
    lx, ly = light
    ln = math.hypot(lx, ly); lx, ly = lx / ln, ly / ln
    pix = {}
    R = int(half + r_out + 2)
    for y in range(int(cy) - R, int(cy) + R + 1):
        for x in range(int(cx) - R, int(cx) + R + 1):
            d, (nx, ny) = seg_dist(x + .5, y + .5, ax, ay, bx, by)
            if r_in <= d <= r_out:
                n = math.hypot(nx, ny) or 1
                nx, ny = nx / n, ny / n
                mid = (r_in + r_out) / 2
                # tube cross-section: outer half faces outward, inner half faces the hole
                w = (d - mid) / ((r_out - r_in) / 2)  # -1 inner .. +1 outer
                dot = (nx * lx + ny * ly) * w
                v = 0.15 + 0.85 * dot
                if v > 0.62: k = 's4'
                elif v > 0.25: k = 's3'
                elif v > -0.2: k = 's2'
                elif v > -0.55: k = 's1'
                else: k = 's0'
                pix[(x, y)] = k
    return pix


def poly_fill(pts):
    xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
    pix = set()
    for y in range(int(min(ys)) - 1, int(max(ys)) + 2):
        for x in range(int(min(xs)) - 1, int(max(xs)) + 2):
            px, py = x + .5, y + .5
            inside = False
            j = len(pts) - 1
            for i in range(len(pts)):
                xi, yi = pts[i]; xj, yj = pts[j]
                if (yi > py) != (yj > py) and px < (xj - xi) * (py - yi) / (yj - yi) + xi:
                    inside = not inside
                j = i
            if inside:
                pix.add((x, y))
    return pix


def shade_bolt(mask):
    pix = {}
    for (x, y) in mask:
        up = (x, y - 1) in mask; dn = (x, y + 1) in mask
        lf = (x - 1, y) in mask; rt = (x + 1, y) in mask
        if not lf or not up:
            k = 'b4' if (not lf and not up) else 'b3'
        elif not rt or not dn:
            k = 'b1' if (not rt and not dn) else 'b1'
        else:
            k = 'b2'
        pix[(x, y)] = k
    # one inner shine streak: second column from left edge
    return pix


def plate(cv, rivets=True):
    w, h = cv.w, cv.h
    for y in range(h):
        for x in range(w):
            if x in (0, w - 1) or y in (0, h - 1):
                k = 'ln'
            elif x in (1, w - 2) or y in (1, h - 2):
                k = 'ln2'
            else:
                k = 'bg'
            cv.col[y][x] = k
            cv.own[y][x] = 'bgdeco'
    if rivets:
        for (x, y) in ((3, 3), (w - 4, 3), (3, h - 4), (w - 4, h - 4)):
            cv.col[y][x] = 'b1'
            cv.col[y + 1][x] = 'K' if False else cv.col[y + 1][x]


def finish(cv):
    """Outline objects against the plate background."""
    add = []
    for y in range(cv.h):
        for x in range(cv.w):
            if cv.own[y][x] == 'bgdeco':
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if cv.inb(nx, ny) and cv.own[ny][nx] not in (None, 'bgdeco') and not str(cv.own[ny][nx]).startswith('outline'):
                        add.append((x, y)); break
    for x, y in add:
        cv.col[y][x] = 'K'
        cv.own[y][x] = 'outline'



# ---------------------------------------------------------------------------
# Mark: two chain links (the peers) bridged by a brass lightning bolt (the link)
# ---------------------------------------------------------------------------
BOLT_ROWS = [
    "......XXXXXXXX",
    "......XXXXXXX.",
    ".....XXXXXXX..",
    ".....XXXXXX...",
    "....XXXXXXX...",
    "....XXXXXX....",
    "...XXXXXX.....",
    "...XXXXXX.....",
    "..XXXXXXXXXXXX",
    "..XXXXXXXXXXX.",
    ".XXXXXXXXXXX..",
    "......XXXXX...",
    ".....XXXXX....",
    ".....XXXX.....",
    "....XXXX......",
    "....XXX.......",
    "...XXX........",
    "...XX.........",
    "..XX..........",
    "..X...........",
]


def bolt_mask(ox, oy):
    return {(ox + x, oy + y) for y, r in enumerate(BOLT_ROWS) for x, c in enumerate(r) if c == 'X'}


def spark(cx, cy):
    return {(cx, cy): 'b4', (cx - 1, cy): 'b2', (cx + 1, cy): 'b2',
            (cx, cy - 1): 'b2', (cx, cy + 1): 'b2'}


def draw_mark(cv, ox=0, oy=0, sparks=True):
    ang = -math.pi / 4
    A = capsule_ring(10 + ox, 22 + oy, 1.6, ang, 5.8, 2.9)
    B = capsule_ring(22 + ox, 10 + oy, 1.6, ang, 5.8, 2.9)
    cv.layer(A, 'A')
    cv.layer(B, 'B')
    bolt = bolt_mask(9 + ox, 6 + oy)
    cv.layer(shade_bolt(bolt), 'bolt')
    if sparks:
        cv.layer(spark(8 + ox, 9 + oy), 'sp1', outline=None)
        cv.layer(spark(24 + ox, 23 + oy), 'sp2', outline=None)


def icon():
    cv = Canvas(32, 32)
    plate(cv)
    draw_mark(cv)
    finish(cv)
    return cv


# ---------------------------------------------------------------------------
# Wordmark: original 6x7 heavy pixel caps, drawn 2x, stepped italic
# ---------------------------------------------------------------------------
GLYPHS = {
    'B': ["XXXXX.", "XX..XX", "XX..XX", "XXXXX.", "XX..XX", "XX..XX", "XXXXX."],
    'L': ["XX....", "XX....", "XX....", "XX....", "XX....", "XX....", "XXXXXX"],
    'I': ["XX", "XX", "XX", "XX", "XX", "XX", "XX"],
    'T': ["XXXXXX", "..XX..", "..XX..", "..XX..", "..XX..", "..XX..", "..XX.."],
    'Z': ["XXXXXX", "....XX", "...XX.", "..XX..", ".XX...", "XX....", "XXXXXX"],
    'N': ["XX..XX", "XXX.XX", "XXXXXX", "XX.XXX", "XX..XX", "XX..XX", "XX..XX"],
    'K': ["XX..XX", "XX.XX.", "XXXX..", "XXX...", "XXXX..", "XX.XX.", "XX..XX"],
}
BRASS_RAMP = ['b4', 'b3', 'b3', 'b3', 'b2', 'b2', 'b2', 'b2', 'b2', 'b1', 'b1', 'b1', 'b0', 'b0']
STEEL_RAMP = ['s4', 's4', 's3', 's3', 's3', 's3', 's2', 's2', 's2', 's2', 's1', 's1', 's1', 's0']


def word(cv, text, x0, y0, ramp, owner, accent=('r', 'rd'), scale=2, gap=3, slant=4):
    H = 7 * scale
    x = x0
    pix = {}
    for ch in text:
        g = GLYPHS[ch]
        for gy, row in enumerate(g):
            for gx, c in enumerate(row):
                if c != 'X':
                    continue
                for sy in range(scale):
                    for sx in range(scale):
                        yy = gy * scale + sy
                        shift = (H - 1 - yy) // slant
                        pix[(x + gx * scale + sx + shift, y0 + yy)] = ramp[yy]
        x += len(g[0]) * scale + gap
    # 2px diagonal extrusion, then one outline around letter + extrusion
    ext = {}
    for (px, py) in pix:
        for d in (1, 2):
            q = (px + d, py + d)
            if q not in pix:
                ext[q] = accent[d - 1]
    m = set(pix) | set(ext)
    out = {}
    for (px, py) in m:
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (px + dx, py + dy)
            if q not in m:
                out[q] = 'K'
    m |= set(out)
    # fill enclosed counters (B, etc.) solid dark
    xs = [p[0] for p in m]; ys = [p[1] for p in m]
    x0b, x1b, y0b, y1b = min(xs) - 1, max(xs) + 1, min(ys) - 1, max(ys) + 1
    seen = {(x0b, y0b)}; stack = [(x0b, y0b)]
    while stack:
        cx, cy = stack.pop()
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            q = (cx + dx, cy + dy)
            if x0b <= q[0] <= x1b and y0b <= q[1] <= y1b and q not in m and q not in seen:
                seen.add(q); stack.append(q)
    for yy in range(y0b, y1b + 1):
        for xx in range(x0b, x1b + 1):
            if (xx, yy) not in m and (xx, yy) not in seen:
                out[(xx, yy)] = 'K'
    cv.layer(out, owner + '_ol', outline=None)
    cv.layer(ext, owner + '_ex', outline=None)
    cv.layer(pix, owner, outline=None)
    return x - gap


def text_width(text, scale=2, gap=3):
    return sum(len(GLYPHS[c][0]) * scale + gap for c in text) - gap


def banner():
    tw = text_width('BLITZ') + 4 + text_width('LINK') + 6  # room for italic + extrusion
    W = 32 + 4 + tw + 8
    cv = Canvas(W, 32)
    plate(cv)
    draw_mark(cv, sparks=True)
    # vertical divider
    for y in range(6, 26):
        cv.col[y][34] = 'ln'
    x = 39
    x = word(cv, 'BLITZ', x, 8, BRASS_RAMP, 'w1')
    word(cv, 'LINK', x + 4, 8, STEEL_RAMP, 'w2', accent=('s1', 's0'))
    finish(cv)
    return cv



# ---------------------------------------------------------------------------
# "Available for <loader>" badges. Icons are original pixel drawings
# (fox / thread spool / bucket), not the loaders' own logos.
# ---------------------------------------------------------------------------
GLYPHS.update({
    'E': ["XXXXXX", "XX....", "XX....", "XXXXX.", "XX....", "XX....", "XXXXXX"],
    'O': [".XXXX.", "XX..XX", "XX..XX", "XX..XX", "XX..XX", "XX..XX", ".XXXX."],
    'F': ["XXXXXX", "XX....", "XX....", "XXXXX.", "XX....", "XX....", "XX...."],
    'R': ["XXXXX.", "XX..XX", "XX..XX", "XXXXX.", "XXXX..", "XX.XX.", "XX..XX"],
    'G': [".XXXXX", "XX....", "XX....", "XX.XXX", "XX..XX", "XX..XX", ".XXXXX"],
    'A': [".XXXX.", "XX..XX", "XX..XX", "XXXXXX", "XX..XX", "XX..XX", "XX..XX"],
    'C': [".XXXXX", "XX....", "XX....", "XX....", "XX....", "XX....", ".XXXXX"],
    'U': ["XX..XX", "XX..XX", "XX..XX", "XX..XX", "XX..XX", "XX..XX", ".XXXX."],
})
SMALL = {
    'A': [".X.", "X.X", "XXX", "X.X", "X.X"],
    'V': ["X.X", "X.X", "X.X", "X.X", ".X."],
    'I': ["X", "X", "X", "X", "X"],
    'L': ["X..", "X..", "X..", "X..", "XXX"],
    'B': ["XX.", "X.X", "XX.", "X.X", "XX."],
    'E': ["XXX", "X..", "XX.", "X..", "XXX"],
    'F': ["XXX", "X..", "XX.", "X..", "X.."],
    'O': ["XXX", "X.X", "X.X", "X.X", "XXX"],
    'R': ["XX.", "X.X", "XX.", "X.X", "X.X"],
    ' ': ["..", "..", "..", "..", ".."],
}
NAME_RAMP = ['p', 'p', 'p', 's4', 's4', 's4', 's4']

# Loader icons (fox face, white banner, bucket) are vanilla Minecraft textures, read from the local client jar.
MC_JAR = os.environ.get('BLITZLINK_MC_JAR') or os.path.expanduser(
    '~/.gradle/caches/neoformruntime/artifacts/minecraft_26.3_client.jar')



def read_png(data):
    """Minimal PNG decoder: non-interlaced, color types 0/2/3/4/6, bit depth 1-8.
    Returns (w, h, rows) with rows[y][x] = (r, g, b, a)."""
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    pos, idat, plte, trns = 8, b'', None, None
    while pos < len(data):
        ln, = struct.unpack('>I', data[pos:pos + 4]); t = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]; pos += 12 + ln
        if t == b'IHDR':
            w, h, bd, ct, _, _, il = struct.unpack('>IIBBBBB', body)
            assert il == 0 and bd <= 8
        elif t == b'PLTE':
            plte = [tuple(body[i:i + 3]) for i in range(0, len(body), 3)]
        elif t == b'tRNS':
            trns = body
        elif t == b'IDAT':
            idat += body
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    bpp = max(1, ch * bd // 8)
    stride = (w * ch * bd + 7) // 8
    raw = zlib.decompress(idat)
    prev = bytearray(stride); rows = []
    for y in range(h):
        f = raw[y * (stride + 1)]
        line = bytearray(raw[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
        for i in range(stride):
            a = line[i - bpp] if i >= bpp else 0
            b = prev[i]; c = prev[i - bpp] if i >= bpp else 0
            if f == 1: line[i] = (line[i] + a) & 255
            elif f == 2: line[i] = (line[i] + b) & 255
            elif f == 3: line[i] = (line[i] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[i] = (line[i] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        prev = line
        # unpack samples
        samples = []
        if bd == 8:
            samples = list(line)
        else:
            per = 8 // bd; mask = (1 << bd) - 1
            for byte in line:
                for k in range(per):
                    samples.append((byte >> (8 - bd * (k + 1))) & mask)
        row = []
        for x in range(w):
            if ct == 3:
                i = samples[x]; r, g, b = plte[i]
                a = trns[i] if trns and i < len(trns) else 255
            elif ct == 0:
                v = samples[x] * 255 // ((1 << bd) - 1); r = g = b = v
                a = 0 if trns and samples[x] == struct.unpack('>H', trns[:2])[0] else 255
            elif ct == 4:
                r = g = b = samples[2 * x]; a = samples[2 * x + 1]
            elif ct == 2:
                r, g, b = samples[3 * x:3 * x + 3]; a = 255
            else:
                r, g, b, a = samples[4 * x:4 * x + 4]
            row.append((r, g, b, a))
        rows.append(row)
    return w, h, rows


def mc_texture(name):
    import zipfile
    if not os.path.exists(MC_JAR):
        sys.exit(f'Minecraft client jar not found: {MC_JAR} (set BLITZLINK_MC_JAR)')
    with zipfile.ZipFile(MC_JAR) as z:
        return read_png(z.read(f'assets/minecraft/textures/{name}.png'))[2]


def fox_face():
    """Front view of the vanilla fox head (8x8): ears + face + snout, cut from entity/fox/fox."""
    t = mc_texture('entity/fox/fox')
    grid = [[None] * 8 for _ in range(8)]
    def blit(u, v, w, h, x, y):
        for dy in range(h):
            for dx in range(w):
                grid[y + dy][x + dx] = t[v + dy][u + dx]
    blit(9, 2, 2, 2, 0, 0)    # ear (texOffs 8,1; front face)
    blit(16, 2, 2, 2, 6, 0)   # ear (texOffs 15,1)
    blit(7, 11, 8, 6, 0, 2)   # head front (texOffs 1,5; 8x6x6)
    blit(9, 21, 4, 2, 2, 6)   # snout front (texOffs 6,18; 4x2x3)
    return grid


def banner_icon(w=10, flag_h=12, pole_h=2):
    """Small front view of a standing white banner, sampled part by part from
    entity/banner/{banner_base,base}: crossbar (texOffs 0,42), flag (texOffs 0,0, 20x40),
    pole (texOffs 44,0). Returns (w) x (2 + flag_h + pole_h) RGBA rows."""
    wood = mc_texture('entity/banner/banner_base')
    cloth = mc_texture('entity/banner/base')
    tint = (0xF9, 0xFF, 0xFE)  # DyeColor.WHITE texture diffuse color
    rows = []
    for v in (44, 45):                                       # crossbar front, 20 -> w
        rows.append([wood[v][2 + x * 20 // w] for x in range(w)])
    for y in range(flag_h):                                  # flag front, 20x40 -> w x flag_h
        row = []
        for x in range(w):
            r, g, b, a = cloth[1 + y * 40 // flag_h][1 + x * 20 // w]
            row.append((r * tint[0] // 255, g * tint[1] // 255, b * tint[2] // 255, 255) if a else None)
        rows.append(row)
    for y in range(pole_h):                                  # pole tip below the flag
        row = [None] * w
        row[w // 2 - 1] = wood[40 + y][46]
        row[w // 2] = wood[40 + y][47]
        rows.append(row)
    return rows


def loader_icon(key):
    if key == 'neoforge':
        g = fox_face()
        return [[g[y // 2][x // 2] for x in range(16)] for y in range(16)]
    if key == 'fabric':
        return [[None] * 3 + r + [None] * 3 for r in banner_icon()]
    return mc_texture('item/bucket')


def stamp_texture(cv, x0, y0, rows, owner):
    pix = {(x0 + x, y0 + y): p for y, r in enumerate(rows) for x, p in enumerate(r)
           if p is not None and p[3] >= 128}
    cv.layer(pix, owner, outline=None)


LOADERS = [('neoforge', 'NEOFORGE'), ('fabric', 'FABRIC'), ('bukkit', 'BUKKIT')]



def small_text(cv, text, x0, y0, col, owner):
    pix, x = {}, x0
    for ch in text:
        g = SMALL[ch]
        for y, r in enumerate(g):
            for gx, c in enumerate(r):
                if c == 'X':
                    pix[(x + gx, y0 + y)] = col
        x += len(g[0]) + 1
    cv.layer(pix, owner, outline=None)


def big_text(cv, text, x0, y0, ramp, owner, gap=1):
    pix, x = {}, x0
    for ch in text:
        g = GLYPHS[ch]
        for y, r in enumerate(g):
            for gx, c in enumerate(r):
                if c == 'X':
                    pix[(x + gx, y0 + y)] = ramp[y]
        x += len(g[0]) + gap
    cv.layer(pix, owner, outline=None)


def badge(key, name):
    names = [n for _, n in LOADERS]
    widest = max(sum(len(GLYPHS[c][0]) + 1 for c in n) - 1 for n in names)
    W = 25 + widest + 4
    cv = Canvas(W, 22)
    plate(cv, rivets=False)
    stamp_texture(cv, 3, 3, loader_icon(key), 'icon')
    for y in range(4, 18):          # divider between icon and text
        cv.col[y][22] = 'ln'
    small_text(cv, 'AVAILABLE FOR', 25, 5, 'mu', 'small')
    big_text(cv, name, 25, 11, NAME_RAMP, 'name')
    finish(cv)
    return cv


if __name__ == '__main__':
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.dirname(os.path.abspath(__file__))
    os.makedirs(out, exist_ok=True)
    ic = icon()
    for s in (1, 4, 16):
        ic.save(os.path.join(out, f'blitzlink-icon-{32 * s}.png'), s)
    bn = banner()
    for s in (1, 4):
        bn.save(os.path.join(out, f'blitzlink-banner-{s}x.png'), s)
    print('banner grid', bn.w, 'x', bn.h)
    for key, name in LOADERS:
        bd = badge(key, name)
        for sc in (1, 2, 3):
            bd.save(os.path.join(out, f'badge-{key}-{sc}x.png'), sc)
    print('badge grid', bd.w, 'x', bd.h)
