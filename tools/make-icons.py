# -*- coding: utf-8 -*-
"""Rasterises Kanal's mark: the leanback banner and the pre-26 launcher icons.

Fire OS launchers do not draw vector banners — Amazon asks for a 320x180 PNG in
drawable-xhdpi — and this project had no raster art at all, so the tile came out
blank on a Fire TV Stick. Rather than paste in binaries nobody can edit, the
PNGs are generated from the same geometry as the vector drawables:

    python tools/make-icons.py app/src/main/res

No image library: a PNG is a zlib stream with a header, and the shapes are
rectangles, a triangle and thick line segments, drawn at 4x and averaged down
for the antialiasing.
"""
import zlib, struct, math, os, sys

S = 4  # supersampling

def write_png(path, w, h, px):
    raw = b''.join(b'\x00' + bytes(px[y * w * 4:(y + 1) * w * 4]) for y in range(h))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    ihdr = struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0)
    with open(path, 'wb') as f:
        f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr)
                + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))

class Canvas:
    def __init__(self, w, h, bg=(0, 0, 0, 0)):
        self.w, self.h = w * S, h * S
        self.ow, self.oh = w, h
        self.buf = bytearray(self.w * self.h * 4)
        for i in range(self.w * self.h):
            self.buf[i*4:i*4+4] = bytes(bg)

    def _blend(self, x, y, c):
        i = (y * self.w + x) * 4
        self.buf[i:i+4] = bytes(c)

    def rrect(self, x, y, w, h, r, c):
        x, y, w, h, r = x*S, y*S, w*S, h*S, r*S
        for py in range(int(y), int(math.ceil(y+h))):
            for px in range(int(x), int(math.ceil(x+w))):
                if not (0 <= px < self.w and 0 <= py < self.h): continue
                cx = min(max(px + .5, x + r), x + w - r)
                cy = min(max(py + .5, y + r), y + h - r)
                if (px + .5 - cx) ** 2 + (py + .5 - cy) ** 2 <= r * r + 1e-9:
                    self._blend(px, py, c)

    def tri(self, p1, p2, p3, c):
        pts = [(p[0]*S, p[1]*S) for p in (p1, p2, p3)]
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        def side(a, b, p):
            return (b[0]-a[0])*(p[1]-a[1]) - (b[1]-a[1])*(p[0]-a[0])
        for py in range(int(min(ys)), int(math.ceil(max(ys)))):
            for px in range(int(min(xs)), int(math.ceil(max(xs)))):
                if not (0 <= px < self.w and 0 <= py < self.h): continue
                p = (px + .5, py + .5)
                d1, d2, d3 = side(pts[0], pts[1], p), side(pts[1], pts[2], p), side(pts[2], pts[0], p)
                if not ((d1 < 0 or d2 < 0 or d3 < 0) and (d1 > 0 or d2 > 0 or d3 > 0)):
                    self._blend(px, py, c)

    def seg(self, a, b, t, c):
        ax, ay, bx, by, t = a[0]*S, a[1]*S, b[0]*S, b[1]*S, t*S/2.0
        x0, x1 = int(min(ax, bx) - t - 1), int(math.ceil(max(ax, bx) + t + 1))
        y0, y1 = int(min(ay, by) - t - 1), int(math.ceil(max(ay, by) + t + 1))
        dx, dy = bx - ax, by - ay
        L2 = dx*dx + dy*dy or 1.0
        for py in range(y0, y1):
            for px in range(x0, x1):
                if not (0 <= px < self.w and 0 <= py < self.h): continue
                u = max(0.0, min(1.0, ((px + .5 - ax) * dx + (py + .5 - ay) * dy) / L2))
                ddx, ddy = px + .5 - (ax + u * dx), py + .5 - (ay + u * dy)
                if ddx*ddx + ddy*ddy <= t*t:
                    self._blend(px, py, c)

    def save(self, path):
        out = bytearray(self.ow * self.oh * 4)
        for y in range(self.oh):
            for x in range(self.ow):
                acc = [0, 0, 0, 0]
                for sy in range(S):
                    row = (y*S + sy) * self.w
                    for sx in range(S):
                        i = (row + x*S + sx) * 4
                        for k in range(4):
                            acc[k] += self.buf[i+k]
                o = (y * self.ow + x) * 4
                n = S * S
                out[o:o+4] = bytes(v // n for v in acc)
        write_png(path, self.ow, self.oh, out)

BG    = (0x07, 0x09, 0x0F, 255)
TEAL  = (0x35, 0xE0, 0xC8, 255)
CYAN  = (0x5A, 0xD9, 0xE8, 255)
VIOL  = (0x7C, 0x6B, 0xFF, 255)
WHITE = (0xFF, 0xFF, 0xFF, 255)

def mark(c, ox, oy, k):
    """The bars-and-play mark, in the 108-unit space of the vector drawable."""
    def X(v): return ox + v * k
    def Y(v): return oy + v * k
    c.rrect(X(24), Y(60), 12*k, 24*k, 3*k, TEAL)
    c.rrect(X(40), Y(44), 12*k, 40*k, 3*k, CYAN)
    c.rrect(X(56), Y(28), 12*k, 56*k, 3*k, VIOL)
    c.tri((X(74), Y(34)), (X(74), Y(86)), (X(100), Y(60)), WHITE)

def word(c, x, y, h, t):
    """KANAL in straight strokes: no font to embed, and it has to be legible
    from the sofa rather than pretty up close."""
    wl, gap = h * 0.62, h * 0.30
    def K(x, col):
        c.seg((x, y), (x, y+h), t, col)
        c.seg((x, y+h/2), (x+wl, y), t, col)
        c.seg((x, y+h/2), (x+wl, y+h), t, col)
    def A(x, col):
        c.seg((x, y+h), (x+wl/2, y), t, col)
        c.seg((x+wl/2, y), (x+wl, y+h), t, col)
        c.seg((x+wl*0.19, y+h*0.62), (x+wl*0.81, y+h*0.62), t, col)
    def N(x, col):
        c.seg((x, y), (x, y+h), t, col)
        c.seg((x+wl, y), (x+wl, y+h), t, col)
        c.seg((x, y), (x+wl, y+h), t, col)
    def L(x, col):
        c.seg((x, y), (x, y+h), t, col)
        c.seg((x, y+h), (x+wl*0.86, y+h), t, col)
    for i, fn in enumerate((K, A, N, A, L)):
        # The L keeps the accent colour it had in the vector banner: it is the
        # only bit of brand in the wordmark and the tile is small.
        fn(x + i * (wl + gap), TEAL if i == 4 else WHITE)

out = sys.argv[1]

# --- banner 320x180, xhdpi ---
b = Canvas(320, 180, BG)
# The mark's own box inside the 108 grid is 76 x 58 units, so it is scaled from
# that and not from the grid, or it comes out half the height it should.
K_BANNER = 74.0 / 58
mark(b, 22 - 24 * K_BANNER, 90 - 57 * K_BANNER, K_BANNER)
# KANAL takes 4.3 times its cap height: at 36 px that is 155, which fits the
# space left over with a margin either side.
word(b, 142, 90 - 18, 36, 7)
os.makedirs(os.path.join(out, 'drawable-xhdpi'), exist_ok=True)
b.save(os.path.join(out, 'drawable-xhdpi', 'banner.png'))
print('banner.png 320x180')

# --- launcher icons, pre-26 densities ---
for folder, size in (('mipmap-mdpi', 48), ('mipmap-hdpi', 72), ('mipmap-xhdpi', 96),
                     ('mipmap-xxhdpi', 144), ('mipmap-xxxhdpi', 192)):
    k = size / 108.0
    c = Canvas(size, size)
    c.rrect(0, 0, size, size, 12 * k, BG)
    # Centred on the mark's own box (x 24..100, y 28..86 of the grid), not on
    # the grid's middle, or it sits low and to the right inside the tile.
    m = k * 0.72
    mark(c, size/2 - 62*m, size/2 - 57*m, m)
    os.makedirs(os.path.join(out, folder), exist_ok=True)
    c.save(os.path.join(out, folder, 'ic_launcher.png'))
    print('%s/ic_launcher.png %dx%d' % (folder, size, size))
