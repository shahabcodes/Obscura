#!/usr/bin/env python3
"""
Builds Obscura's Lottie motion kit: eight small geometric animations, written as Lottie JSON to
app/src/main/res/raw/motion_*.json and played offline by lottie-compose.

The shapes, colours and timings follow the Prism motion kit in the design canvas (SVG plus CSS
keyframes). Run it from anywhere after changing an animation:

    python tools/lottie/generate_motion.py

Layer names are part of the contract with ui/components/Motion.kt, which can recolour a layer by
name so the same animation sits on different backgrounds.
"""
import json
import math
import os
import re

FPS = 60
OUT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'app', 'src', 'main', 'res', 'raw'))

INK, PAPER, SUN, PINK = '#0B0B0C', '#F5F5F0', '#FFE14D', '#FF7AB6'
MINT, COBALT, ORANGE, LINE, STONE = '#5EF0C2', '#3B5BFF', '#FF8A3D', '#2A2A30', '#D6D6CF'

# CSS timing functions as cubic-bezier control points.
EASES = {
    'linear': (0.0, 0.0, 1.0, 1.0),
    'ease': (0.25, 0.1, 0.25, 1.0),
    'ease-in-out': (0.42, 0.0, 0.58, 1.0),
    'ease-out': (0.0, 0.0, 0.58, 1.0),
    'snap': (0.6, 0.0, 0.2, 1.0),  # cubic-bezier(.6,0,.2,1), the kit's main curve
    'pop': (0.2, 0.8, 0.2, 1.0),  # cubic-bezier(.2,.8,.2,1)
}


# ---- Lottie building blocks ----

def color(hex_value):
    h = hex_value.lstrip('#')
    return [round(int(h[i:i + 2], 16) / 255, 4) for i in (0, 2, 4)] + [1]


def static(value):
    return {'a': 0, 'k': value}


def keyframes(keys, ease='snap'):
    """keys: [(frame, value)] or [(frame, value, ease of the segment that starts here)].

    The ease 'hold' keeps the value until the next key. Before the first key Lottie shows the
    first value, after the last key the last value.
    """
    out = []
    for n, key in enumerate(keys):
        frame, value = key[0], key[1]
        k = {'t': frame, 's': value if isinstance(value, list) else [value]}
        if n < len(keys) - 1:
            e = key[2] if len(key) > 2 else ease
            if e == 'hold':
                k['h'] = 1
            else:
                x1, y1, x2, y2 = EASES[e]
                k['o'] = {'x': [x1], 'y': [y1]}
                k['i'] = {'x': [x2], 'y': [y2]}
        out.append(k)
    return {'a': 1, 'k': out}


def sc(percent):
    return [percent, percent, 100]


def transform(anchor, position=None, scale=None, rotation=None, opacity=None):
    """Layer transform. The anchor is the transform origin, in composition coordinates."""
    return {
        'o': opacity if isinstance(opacity, dict) else static(100 if opacity is None else opacity),
        'r': rotation if isinstance(rotation, dict) else static(0 if rotation is None else rotation),
        'p': position if isinstance(position, dict) else static(list(position or anchor) + [0]),
        'a': static(list(anchor) + [0]),
        's': scale if isinstance(scale, dict) else static(sc(100 if scale is None else scale)),
    }


def rect(x, y, w, h, r=0):
    return {'ty': 'rc', 'nm': 'Rect', 'd': 1, 'p': static([x + w / 2, y + h / 2]), 's': static([w, h]), 'r': static(r)}


def circle(cx, cy, r):
    return {'ty': 'el', 'nm': 'Ellipse', 'd': 1, 'p': static([cx, cy]), 's': static([2 * r, 2 * r])}


def fill(value, opacity=100):
    c = value if isinstance(value, dict) else static(color(value))
    return {'ty': 'fl', 'nm': 'Fill', 'c': c, 'o': static(opacity), 'r': 1, 'bm': 0}


def stroke(value, width, opacity=100):
    return {'ty': 'st', 'nm': 'Stroke', 'c': static(color(value)), 'o': static(opacity), 'w': static(width),
            'lc': 2, 'lj': 2, 'ml': 4, 'bm': 0}


def trim(end):
    return {'ty': 'tm', 'nm': 'Trim', 's': static(0), 'e': end, 'o': static(0), 'm': 1}


def group_transform(position=(0, 0)):
    p = position if isinstance(position, dict) else static(list(position))
    return {'ty': 'tr', 'nm': 'Transform', 'p': p, 'a': static([0, 0]), 's': static([100, 100]), 'r': static(0),
            'o': static(100), 'sk': static(0), 'sa': static(0)}


def group(name, items, position=(0, 0)):
    """Shape group. Within a list of shapes, the first one is drawn on top."""
    return {'ty': 'gr', 'nm': name, 'np': len(items), 'bm': 0, 'it': items + [group_transform(position)]}


def layer(name, shapes, ks, frames, masks=None, parent=None):
    out = {'ddd': 0, 'ty': 4, 'nm': name, 'sr': 1, 'ks': ks, 'ao': 0, 'shapes': shapes, 'ip': 0, 'op': frames, 'st': 0, 'bm': 0}
    if masks:
        out['hasMask'] = True
        out['masksProperties'] = masks
    if parent is not None:
        out['parent'] = parent
    return out


def null_layer(name, ks, frames):
    return {'ddd': 0, 'ty': 3, 'nm': name, 'sr': 1, 'ks': ks, 'ao': 0, 'ip': 0, 'op': frames, 'st': 0, 'bm': 0}


def composition(name, w, h, frames, layers):
    """Layers are listed top first. Indices are assigned in order."""
    for n, item in enumerate(layers, start=1):
        item['ind'] = n
    return {'v': '5.7.4', 'fr': FPS, 'ip': 0, 'op': frames, 'w': w, 'h': h, 'nm': name, 'ddd': 0,
            'assets': [], 'layers': layers, 'markers': []}


# ---- SVG path data to Lottie bezier shapes ----

TOKEN = re.compile(r'[MmLlHhVvCcSsAaZz]|[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?')


def arc(x1, y1, rx, ry, angle, large, sweep, x2, y2):
    """SVG elliptical arc to cubic segments [(c1, c2, end)], per the SVG implementation notes."""
    if (x1, y1) == (x2, y2):
        return []
    phi = math.radians(angle)
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = cos_p * dx + sin_p * dy, -sin_p * dx + cos_p * dy
    rx, ry = abs(rx), abs(ry)
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    coef = math.sqrt(max(0.0, num / den))
    if large == sweep:
        coef = -coef
    cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
    cx = cos_p * cxp - sin_p * cyp + (x1 + x2) / 2
    cy = sin_p * cxp + cos_p * cyp + (y1 + y2) / 2

    def angle_between(ux, uy, vx, vy):
        return math.atan2(ux * vy - uy * vx, ux * vx + uy * vy)

    start = angle_between(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    delta = angle_between((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if not sweep and delta > 0:
        delta -= 2 * math.pi
    elif sweep and delta < 0:
        delta += 2 * math.pi
    count = max(1, math.ceil(abs(delta) / (math.pi / 2) - 1e-9))
    step = delta / count
    k = 4 / 3 * math.tan(step / 4)

    def place(px, py):
        px, py = px * rx, py * ry
        return cos_p * px - sin_p * py + cx, sin_p * px + cos_p * py + cy

    segments = []
    for i in range(count):
        a1, a2 = start + i * step, start + (i + 1) * step
        c1 = place(math.cos(a1) - k * math.sin(a1), math.sin(a1) + k * math.cos(a1))
        c2 = place(math.cos(a2) + k * math.sin(a2), math.sin(a2) - k * math.cos(a2))
        segments.append((c1, c2, place(math.cos(a2), math.sin(a2))))
    return segments


def shape(nodes, closed):
    r = lambda v: round(v, 3)
    return {'c': closed, 'v': [[r(n[0]), r(n[1])] for n in nodes], 'i': [[r(n[2]), r(n[3])] for n in nodes],
            'o': [[r(n[4]), r(n[5])] for n in nodes]}


def path(d):
    """SVG path data (M L H V C S A Z, absolute or relative) to Lottie path shapes, one per subpath."""
    tokens = TOKEN.findall(d)
    shapes, nodes = [], []  # node: [x, y, in dx, in dy, out dx, out dy]
    state = {'closed': False}
    x = y = 0.0
    start = (0.0, 0.0)
    last_c2 = None
    cmd = None
    i = 0

    def flush():
        if nodes:
            if state['closed'] and len(nodes) > 1 and math.isclose(nodes[0][0], nodes[-1][0], abs_tol=1e-6) \
                    and math.isclose(nodes[0][1], nodes[-1][1], abs_tol=1e-6):
                # The path ends where it started: that last curve's handle belongs to the first point.
                nodes[0][2], nodes[0][3] = nodes[-1][2], nodes[-1][3]
                nodes.pop()
            shapes.append({'ty': 'sh', 'nm': 'Path', 'd': 1, 'ks': static(shape(nodes, state['closed']))})
        nodes.clear()
        state['closed'] = False

    def num():
        nonlocal i
        value = float(tokens[i])
        i += 1
        return value

    def line_to(nx, ny):
        nonlocal x, y
        nodes.append([nx, ny, 0.0, 0.0, 0.0, 0.0])
        x, y = nx, ny

    def cubic_to(c1, c2, p):
        nonlocal x, y
        nodes[-1][4], nodes[-1][5] = c1[0] - x, c1[1] - y
        nodes.append([p[0], p[1], c2[0] - p[0], c2[1] - p[1], 0.0, 0.0])
        x, y = p

    while i < len(tokens):
        if tokens[i].isalpha():
            cmd = tokens[i]
            i += 1
        elif cmd is None or cmd in 'Zz':
            raise ValueError('Numbers without a command in ' + d)
        upper = cmd.upper()
        ox, oy = (x, y) if cmd.islower() else (0.0, 0.0)
        if upper == 'M':
            flush()
            x, y = num() + ox, num() + oy
            nodes.append([x, y, 0.0, 0.0, 0.0, 0.0])
            start = (x, y)
            cmd = 'l' if cmd == 'm' else 'L'  # more coordinate pairs are implicit line-tos
            last_c2 = None
        elif upper == 'L':
            line_to(num() + ox, num() + oy)
            last_c2 = None
        elif upper == 'H':
            line_to(num() + ox, y)
            last_c2 = None
        elif upper == 'V':
            line_to(x, num() + oy)
            last_c2 = None
        elif upper == 'C':
            c1 = (num() + ox, num() + oy)
            c2 = (num() + ox, num() + oy)
            cubic_to(c1, c2, (num() + ox, num() + oy))
            last_c2 = c2
        elif upper == 'S':
            c1 = (2 * x - last_c2[0], 2 * y - last_c2[1]) if last_c2 else (x, y)
            c2 = (num() + ox, num() + oy)
            cubic_to(c1, c2, (num() + ox, num() + oy))
            last_c2 = c2
        elif upper == 'A':
            rx, ry, angle, large, sweep = num(), num(), num(), num(), num()
            ex, ey = num() + ox, num() + oy
            for c1, c2, end in arc(x, y, rx, ry, angle, int(large), int(sweep), ex, ey):
                cubic_to(c1, c2, end)
            last_c2 = None
        elif upper == 'Z':
            state['closed'] = True
            x, y = start
            last_c2 = None
            flush()
        else:
            raise ValueError('Unsupported path command ' + cmd)
    flush()
    return shapes


def rounded_rect_mask(x, y, w, h, r):
    k = 0.5523 * r
    v = [(x + r, y), (x + w - r, y), (x + w, y + r), (x + w, y + h - r), (x + w - r, y + h), (x + r, y + h), (x, y + h - r), (x, y + r)]
    tin = [(-k, 0), (0, 0), (0, -k), (0, 0), (k, 0), (0, 0), (0, k), (0, 0)]
    tout = [(0, 0), (k, 0), (0, 0), (0, k), (0, 0), (-k, 0), (0, 0), (0, -k)]
    nodes = [[vx, vy, ix, iy, ox, oy] for (vx, vy), (ix, iy), (ox, oy) in zip(v, tin, tout)]
    return {'inv': False, 'mode': 'a', 'nm': 'Mask', 'pt': static(shape(nodes, True)), 'o': static(100), 'x': static(0)}


# ---- CSS keyframes sampled into a seamless loop ----

def bezier_ease(name, u):
    x1, y1, x2, y2 = EASES[name]
    lo, hi = 0.0, 1.0
    for _ in range(40):
        t = (lo + hi) / 2
        bx = 3 * (1 - t) ** 2 * t * x1 + 3 * (1 - t) * t ** 2 * x2 + t ** 3
        lo, hi = (t, hi) if bx < u else (lo, t)
    t = (lo + hi) / 2
    return 3 * (1 - t) ** 2 * t * y1 + 3 * (1 - t) * t ** 2 * y2 + t ** 3


def css(stops, ease, p):
    """CSS keyframes [(fraction, value)] with one timing function per segment, at fraction p."""
    for (p0, v0), (p1, v1) in zip(stops, stops[1:]):
        if p <= p1:
            u = 0.0 if p1 == p0 else (p - p0) / (p1 - p0)
            e = bezier_ease(ease, min(1.0, max(0.0, u)))
            if isinstance(v0, (list, tuple)):
                return [a + (b - a) * e for a, b in zip(v0, v1)]
            return v0 + (v1 - v0) * e
    return stops[-1][1]


def rounded(value):
    return [round(v, 2) for v in value] if isinstance(value, list) else round(value, 2)


def sampled(stops, ease, frames, delay, to_value, step=3):
    """An infinitely repeating CSS animation that starts `delay` frames late, as linear keys."""
    keys = []
    for f in list(range(0, frames, step)) + [frames]:
        keys.append((f, rounded(to_value(css(stops, ease, ((f - delay) % frames) / frames))), 'linear'))
    return keyframes(keys)


# ---- The eight animations ----

def unlock():
    """1.2 s. The shackle lifts and swings open, the body lights up, then the lock settles."""
    f = 72
    lock = null_layer('lock', transform((60, 80), scale=keyframes([(40, sc(100), 'ease-out'), (50, sc(106), 'ease-out'), (62, sc(100))])), f)
    shackle = layer('shackle', [group('shackle', path('M40 58 V40 a20 20 0 0 1 40 0 V58') + [stroke(PAPER, 9)])],
                    transform((74, 58), position=keyframes([(12, [74, 58, 0]), (42, [74, 48, 0])]),
                              rotation=keyframes([(12, 0), (42, -28)])), f, parent=1)
    body = layer('body', [group('body', [rect(28, 56, 64, 48, 12), fill(keyframes([(18, color(PAPER)), (42, color(SUN))]))])],
                 transform((60, 80)), f, parent=1)
    keyhole = layer('keyhole', [group('keyhole', [circle(60, 78, 7), fill(COBALT)])], transform((60, 78)), f, parent=1)
    return composition('unlock', 120, 120, f, [lock, keyhole, body, shackle])


def seal():
    """2.2 s loop. A file drops into the slot of the vault box and disappears inside."""
    f = 132
    drop = layer('drop', [group('drop', [rect(44, 22, 32, 40, 5), fill(PINK)])],
                 transform((60, 62),
                           position=keyframes([(0, [60, 2, 0]), (73, [60, 62, 0]), (92, [60, 80, 0])]),
                           scale=keyframes([(73, sc(100)), (92, sc(60))]),
                           opacity=keyframes([(0, 0), (26, 100, 'hold'), (73, 100), (92, 0)])), f)
    box = layer('box', [group('box', [rect(20, 66, 80, 44, 12), fill(INK)])], transform((60, 88)), f)
    slot = layer('slot', [group('slot', [rect(36, 62, 48, 8, 4), fill(INK)])],
                 transform((60, 66), scale=keyframes([(66, [100, 100, 100]), (82, [112, 100, 100]), (99, [100, 100, 100])], 'ease')), f)
    hole = layer('hole', [group('hole', [circle(60, 88, 8), fill(SUN)])], transform((60, 88)), f)
    return composition('seal', 120, 120, f, [hole, slot, box, drop])


def blip(name, cx, cy, r, colour, start, frames):
    """A dot that pops in and fades, starting at `start` and lasting 36 frames."""
    return layer(name, [group(name, [circle(cx, cy, r), fill(colour)])],
                 transform((cx, cy), scale=keyframes([(start, sc(40)), (start + 10, sc(120)), (start + 36, sc(40))], 'ease-in-out'),
                           opacity=keyframes([(start, 0), (start + 10, 100), (start + 36, 0)], 'ease-in-out')), frames)


def favorite():
    """0.9 s. The heart pops with a beat, a ring and three dots burst, then everything fades."""
    f = 54
    heart = 'M60 92s-30-19-30-43a17 17 0 0 1 30-11 17 17 0 0 1 30 11c0 24-30 43-30 43z'
    ring = layer('ring', [group('ring', [circle(60, 60, 34), stroke(PINK, 5)])],
                 transform((60, 60), scale=keyframes([(0, sc(40)), (38, sc(170))], 'ease-out'),
                           opacity=keyframes([(0, 90), (38, 0)], 'ease-out')), f)
    beat = layer('heart', [group('heart', path(heart) + [fill(PINK)])],
                 transform((60, 62), scale=keyframes([(0, sc(0)), (8, sc(125)), (16, sc(95)), (24, sc(108)), (32, sc(100))], 'ease-out'),
                           opacity=keyframes([(38, 100), (54, 0)], 'ease-out')), f)
    dots = [blip('blip3', 96, 98, 5, COBALT, 16, f), blip('blip2', 100, 30, 4, MINT, 10, f), blip('blip1', 22, 28, 5, SUN, 4, f)]
    return composition('favorite', 120, 120, f, dots + [beat, ring])


def scan():
    """2.2 s loop. A radar sweep over the dark disc, with two findings blinking."""
    f = 132
    disc = layer('disc', [group('ring_outer', [circle(80, 80, 46), stroke(LINE, 1.5)]),
                          group('ring_inner', [circle(80, 80, 22), stroke(LINE, 1.5)]),
                          group('disc', [circle(80, 80, 70), fill(INK)])], transform((80, 80)), f)
    sweep = layer('sweep', [group('sweep', path('M80 80 L80 10 A70 70 0 0 1 140 45 Z') + [fill(ORANGE, 45)])],
                  transform((80, 80), rotation=keyframes([(0, 0), (132, 360)], 'linear')), f)
    stops = [(0, [40, 0]), (0.35, [120, 100]), (1, [40, 0])]  # scale %, opacity
    first = layer('blip1', [group('blip1', [circle(112, 58, 6), fill(SUN)])],
                  transform((112, 58), scale=sampled(stops, 'ease-in-out', f, 0, lambda v: sc(v[0])),
                            opacity=sampled(stops, 'ease-in-out', f, 0, lambda v: v[1])), f)
    second = layer('blip2', [group('blip2', [circle(52, 110, 5), fill(SUN)])],
                   transform((52, 110), scale=sampled(stops, 'ease-in-out', f, 60, lambda v: sc(v[0])),
                             opacity=sampled(stops, 'ease-in-out', f, 60, lambda v: v[1])), f)
    return composition('scan', 160, 160, f, [second, first, sweep, disc])


def success():
    """1.4 s. The mint disc pops in, a ring ripples out and the check mark draws itself."""
    f = 84
    ring = layer('ring', [group('ring', [circle(60, 60, 48), stroke(MINT, 4)])],
                 transform((60, 60), scale=keyframes([(10, sc(100)), (50, sc(120))], 'ease-out'),
                           opacity=keyframes([(0, 0, 'hold'), (10, 70), (50, 0)], 'ease-out')), f)
    disc = layer('circle', [group('circle', [circle(60, 60, 48), fill(MINT)])],
                 transform((60, 60), scale=keyframes([(0, sc(50), 'pop'), (16, sc(106), 'ease-in-out'), (26, sc(100))]),
                           opacity=keyframes([(0, 0), (6, 100)], 'linear')), f)
    check = layer('check', [group('check', path('M38 61 L53 76 L83 45') + [trim(keyframes([(14, 0), (48, 100)])), stroke(INK, 9)])],
                  transform((60, 60)), f)
    return composition('success', 120, 120, f, [check, disc, ring])


def empty():
    """3 s loop. A circle, a square and a triangle drift up and tilt, one after another."""
    f = 180
    stops = [(0, [0, 0]), (0.5, [-12, 8]), (1, [0, 0])]  # translateY, rotation

    def bobbing(name, items, cx, cy, delay):
        return layer(name, [group(name, items)],
                     transform((cx, cy), position=sampled(stops, 'ease-in-out', f, delay, lambda v: [cx, cy + v[0], 0]),
                               rotation=sampled(stops, 'ease-in-out', f, delay, lambda v: v[1])), f)

    shapes = [
        bobbing('triangle', path('M60 100 L80 70 L100 100 Z') + [fill(INK)], 80, 85, 72),
        bobbing('square', [rect(76, 30, 34, 34, 6), fill(PINK)], 93, 47, 36),
        bobbing('circle', [circle(40, 50, 22), fill(COBALT)], 40, 50, 0),
    ]
    return composition('empty', 140, 120, f, shapes)


def decrypt():
    """1.3 s loop. A grid of tiles: some revealed in colour, the rest shimmering while they decrypt."""
    f = 78
    size, gap, ox, oy = 34, 5, 4, 3.5
    tiles = [(0, 0, None, 0), (1, 0, COBALT, None), (2, 0, None, 18), (0, 1, PINK, None), (1, 1, None, 36), (2, 1, MINT, None)]
    layers = []
    for n, (col, row, colour, delay) in enumerate(tiles, start=1):
        x, y = ox + col * (size + gap), oy + row * (size + gap)
        if colour:
            layers.append(layer('tile%d' % n, [group('tile', [rect(x, y, size, size, 6), fill(colour)])], transform((x + 17, y + 17)), f))
            continue
        left, right, span = -20.0, 40.0, 60.0  # the bar starts and ends fully outside the tile

        def at(frame):
            return [round(left + span * (((frame - delay) % f) / f), 3), 0]

        if delay == 0:
            bar = keyframes([(0, [left, 0], 'linear'), (f, [right, 0])])
        else:
            bar = keyframes([(0, at(0), 'linear'), (delay, [right, 0], 'hold'), (delay + 1, at(delay + 1), 'linear'), (f, at(f))])
        layers.append(layer('shimmer%d' % n, [
            group('bar', [rect(x, y, size * 0.4, size), fill(PAPER, 70)], position=bar),
            group('tile', [rect(x, y, size, size, 6), fill(STONE)]),
        ], transform((0, 0)), f, masks=[rounded_rect_mask(x, y, size, size, 6)]))
    return composition('decrypt', 120, 80, f, list(reversed(layers)))


def panic():
    """0.6 s. Two shock rings and a lightning bolt with a beat: locked, instantly."""
    f = 36

    def ring(name, start):
        opacity = [(0, 90), (26, 0)] if start == 0 else [(0, 0, 'hold'), (start, 90), (start + 26, 0)]
        return layer(name, [group(name, [circle(60, 60, 40), stroke(INK, 4)])],
                     transform((60, 60), scale=keyframes([(start, sc(40)), (start + 26, sc(145))], 'ease-out'),
                               opacity=keyframes(opacity, 'ease-out')), f)

    bolt = layer('bolt', [group('bolt', path('M64 18 L38 64 h18 l-4 38 28-48 H62 z') + [fill(INK)])],
                 transform((59, 60), scale=keyframes([(0, sc(50)), (6, sc(125)), (12, sc(95)), (18, sc(108)), (24, sc(100))], 'ease-out')), f)
    return composition('panic', 120, 120, f, [bolt, ring('ring2', 8), ring('ring1', 0)])


ANIMATIONS = {
    'unlock': unlock, 'seal': seal, 'favorite': favorite, 'scan': scan,
    'success': success, 'empty': empty, 'decrypt': decrypt, 'panic': panic,
}


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, build in ANIMATIONS.items():
        data = build()
        target = os.path.join(OUT, 'motion_%s.json' % name)
        with open(target, 'w', encoding='utf-8', newline='\n') as out:
            json.dump(data, out, separators=(',', ':'))
            out.write('\n')
        print('%-9s %4.2fs %3dx%-3d %6d bytes  %s' % (name, data['op'] / FPS, data['w'], data['h'], os.path.getsize(target), target))


if __name__ == '__main__':
    main()
