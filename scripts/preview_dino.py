#!/usr/bin/env python3
"""
Pixel-dino run cycle for the parental-limit lock screen.

The silhouette is drawn geometrically (ellipses + polygons) on a 22x19 pixel
grid rather than hand-written ASCII art, so it is always well-formed and
actually reads as a T-rex.

Generates:
  preview/frames/dino_NN.png   - full lock-screen-sized frames (preview only)
  preview/sprite/dino_NN.png   - the 22x19 sprite frames (the real app assets)
  preview/dino_run.gif         - standalone animated GIF
  preview/dino_sheet.png       - contact sheet of the 4 frames
  preview/lockscreen.html      - a mockup of the lock screen
"""
import math
import os
import struct
import subprocess
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "preview")
FRAMES = os.path.join(OUT, "frames")
SPRITES = os.path.join(OUT, "sprite")

W, H = 22, 22

INK = "X"
EYE = "o"


def in_ellipse(x, y, cx, cy, rx, ry):
    if rx <= 0 or ry <= 0:
        return False
    return ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 <= 1.0


def in_polygon(x, y, points):
    """Even-odd point-in-polygon test."""
    inside = False
    n = len(points)
    j = n - 1
    for i in range(n):
        xi, yi = points[i]
        xj, yj = points[j]
        if ((yi > y) != (yj > y)) and \
                (x < (xj - xi) * (y - yi) / (yj - yi + 1e-9) + xi):
            inside = not inside
        j = i
    return inside


def dino_mask(leg_frame=0):
    """Return a 22x19 grid of characters for one run frame.

    leg_frame: 0 = legs together, 1 = front leg forward, 2 = back leg forward.
    """
    grid = [['.'] * W for _ in range(H)]

    def fill(x0, x1, y0, y1, ch=INK):
        for y in range(max(0, y0), min(H, y1 + 1)):
            for x in range(max(0, x0), min(W, x1 + 1)):
                grid[y][x] = ch

    def carve(x0, x1, y0, y1):
        for y in range(max(0, y0), min(H, y1 + 1)):
            for x in range(max(0, x0), min(W, x1 + 1)):
                grid[y][x] = '.'

    # The silhouette is described per row as a list of inclusive column spans.
    # Building it this way guarantees every row is exactly 22 wide.
    #
    # Proportions follow the classic browser offline-game dino: a big head that
    # juts to the upper right, a thick body running down the left, and the
    # tail filling the lower-left corner.
    spans = [
        [(8, 13)],        # 0  crown of the head
        [(7, 14)],        # 1
        [(6, 15)],        # 2
        [(6, 16)],        # 3
        [(6, 17)],        # 4  eye row
        [(5, 18)],        # 5  snout
        [(5, 18)],        # 6
        [(5, 18)],        # 7
        [(5, 17)],        # 8  jaw
        [(6, 15)],        # 9  neck
        [(6, 13)],        # 10
        [(5, 12)],        # 11 shoulder
        [(3, 12)],        # 12 back
        [(1, 12)],        # 13
        [(0, 12)],        # 14
        [(0, 12)],        # 15
        [(0, 12)],        # 16
        [(0, 12)],        # 17
        [(0, 12)],        # 18
        [(1, 12)],        # 19
        [(2, 12)],        # 20
        [(3, 12)],        # 21
    ]

    for y, row_spans in enumerate(spans):
        for x0, x1 in row_spans:
            fill(x0, x1, y, y)

    # --- eye ---
    carve(10, 11, 4, 5)
    grid[4][10] = EYE
    grid[5][10] = EYE

    # --- mouth: a notch under the snout ---
    carve(14, 17, 9, 9)

    # --- small arm in front of the body ---
    if leg_frame != 2:
        fill(11, 12, 10, 11)
    else:
        fill(10, 11, 11, 12)

    # --- legs: carved out of the lower body so they read as two limbs ---
    if leg_frame == 0:              # together
        carve(7, 8, 18, 21)
    elif leg_frame == 1:            # front leg forward, back leg trailing
        carve(6, 8, 18, 20)
        fill(2, 6, 20, 21)
        carve(7, 8, 19, 21)
    else:                           # back leg forward, front leg trailing
        carve(7, 8, 18, 20)
        fill(9, 13, 20, 21)
        carve(6, 8, 19, 21)

    return ["".join(r) for r in grid]


# 4-frame run cycle.
FRAMES_SPEC = [0, 1, 2, 1]


# ── PNG writer ────────────────────────────────────────────────────────────
def write_png(path, width, height, pixels):
    raw = bytearray()
    for row in pixels:
        raw.append(0)
        for (r, g, b, a) in row:
            raw += bytes((r, g, b, a))

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b"\x89PNG\r\n\x1a\n"
    png += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    png += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png)


def sprite_pixels(grid, scale, color=(0xE8, 0xE8, 0xEA, 0xFF),
                  eye=(0x10, 0x10, 0x14, 0xFF)):
    h, w = len(grid), len(grid[0])
    px = [[(0, 0, 0, 0)] * (w * scale) for _ in range(h * scale)]
    for y, row in enumerate(grid):
        for x, ch in enumerate(row):
            if ch == ".":
                continue
            c = color if ch == "X" else eye
            for dy in range(scale):
                for dx in range(scale):
                    px[y * scale + dy][x * scale + dx] = c
    return px


def blit(dst, src, ox, oy):
    for y, row in enumerate(src):
        for x, c in enumerate(row):
            if c[3]:
                dst[oy + y][ox + x] = c


def draw_dashes(dst, offset, ground_y, color=(0x46, 0x46, 0x52, 0xFF), gap=22, dash=10):
    w = len(dst[0])
    x = -offset % gap
    while x < w:
        for i in range(dash):
            px = x + i
            if 0 <= px < w and ground_y < len(dst):
                dst[ground_y][px] = color
        x += gap


def draw_cactus(dst, ox, ground_y, scale, color=(0x7A, 0xC8, 0x7A, 0xFF)):
    shape = ["..X..", ".XXX.", "XXXXX", ".XXX.", ".XXX.", ".XXX."]
    for y, row in enumerate(shape):
        for x, ch in enumerate(row):
            if ch != "X":
                continue
            for dy in range(scale):
                for dx in range(scale):
                    yy = ground_y - (len(shape) - y) * scale + dy
                    xx = ox + x * scale + dx
                    if 0 <= xx < len(dst[0]) and 0 <= yy < len(dst):
                        dst[yy][xx] = color


def build():
    os.makedirs(FRAMES, exist_ok=True)
    os.makedirs(SPRITES, exist_ok=True)

    SCALE = 4
    CANVAS_W, CANVAS_H = 320, 104
    GROUND_Y = 88
    DINO_X = 26

    for i, spec in enumerate(FRAMES_SPEC):
        grid = dino_mask(spec)

        canvas = [[(0, 0, 0, 0)] * CANVAS_W for _ in range(CANVAS_H)]
        draw_dashes(canvas, i * 12, GROUND_Y)

        cactus_x = 205 - i * 26
        if -24 < cactus_x < CANVAS_W:
            draw_cactus(canvas, cactus_x, GROUND_Y, 2)

        dino = sprite_pixels(grid, SCALE)
        blit(canvas, dino, DINO_X, GROUND_Y - len(dino))
        write_png(os.path.join(FRAMES, f"dino_{i:02d}.png"), CANVAS_W, CANVAS_H, canvas)

    print(f"[+] {len(FRAMES_SPEC)} preview frames -> {FRAMES}")

    for i, spec in enumerate(FRAMES_SPEC):
        grid = dino_mask(spec)
        px = sprite_pixels(grid, 1)
        write_png(os.path.join(SPRITES, f"dino_{i:02d}.png"), W, len(grid), px)
    print(f"[+] sprite frames -> {SPRITES}")

    gif = os.path.join(OUT, "dino_run.gif")
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-framerate", "6",
         "-i", os.path.join(FRAMES, "dino_%02d.png"),
         "-vf", "split[a][b];[a]palettegen=max_colors=64[p];[b][p]paletteuse=dither=none",
         "-loop", "0", gif],
        check=True,
    )
    print(f"[+] gif -> {gif}")

    # contact sheet
    S = 6
    GAP = 10
    tw = W * S
    sheet_w = len(FRAMES_SPEC) * (tw + GAP)
    sheet_h = H * S
    sheet = [[(0x10, 0x10, 0x14, 0xFF)] * sheet_w for _ in range(sheet_h)]
    ox = 0
    for spec in FRAMES_SPEC:
        blit(sheet, sprite_pixels(dino_mask(spec), S), ox, 0)
        ox += tw + GAP
    write_png(os.path.join(OUT, "dino_sheet.png"), sheet_w, sheet_h, sheet)
    print(f"[+] contact sheet -> {os.path.join(OUT, 'dino_sheet.png')}")

    with open(os.path.join(OUT, "lockscreen.html"), "w") as f:
        f.write(HTML_MOCKUP)
    print(f"[+] mockup -> {os.path.join(OUT, 'lockscreen.html')}")


HTML_MOCKUP = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>SmallTube - 家长限时锁屏预览</title>
<style>
  body { margin:0; background:#0b0b0f; color:#e8e8ea;
         font-family:-apple-system,"PingFang SC","Helvetica Neue",sans-serif; }
  .wrap { padding: 40px 20px; }
  h1 { font-size:22px; font-weight:600; margin:0 0 6px; }
  .sub { color:#8a8a95; font-size:14px; margin:0 0 28px; }
  .screen { background:#101014; border-radius:10px; max-width:960px; margin:0 auto 34px;
            padding:44px 40px 0; position:relative; overflow:hidden; }
  .title { text-align:center; font-size:30px; font-weight:700; color:#fff; }
  .msg { text-align:center; font-size:17px; color:#c8c8ce; margin-top:14px; }
  .count { text-align:center; font-size:64px; font-weight:700; color:#fff;
           font-family:ui-monospace,Menlo,monospace; margin-top:18px; }
  .rem { text-align:center; font-size:19px; color:#9ae6b4; margin-top:8px; }
  .btn { display:block; margin:24px auto 0; padding:12px 26px; background:#1f6feb;
         color:#fff; border-radius:8px; font-size:16px; text-align:center; width:max-content; }
  .dino { margin-top:20px; margin-left:-40px; margin-right:-40px; background:#101014; }
  .dino img { display:block; width:100%; height:auto; }
  .opts { max-width:960px; margin:0 auto 26px; background:#14141a; border-radius:10px; padding:18px 24px; }
  .note { max-width:960px; margin:0 auto; color:#8a8a95; font-size:13px; line-height:1.9; }
  .note b { color:#c8c8ce; }
  code { background:#1c1c22; padding:2px 6px; border-radius:4px; font-size:12px; }
</style>
</head>
<body>
<div class="wrap">
  <h1>家长限时 · 锁屏预览</h1>
  <p class="sub">休息期间的像素恐龙 —— 暗示「该去做点别的了」</p>

  <div class="screen">
    <div class="title">今天先到这里</div>
    <div class="msg">SmartTube 正在休息中，请稍后再来。</div>
    <div class="count">24:37</div>
    <div class="rem">还需 25 分钟才能继续观看</div>
    <div class="btn">家长操作</div>
    <div class="dino"><img src="dino_run.gif" alt="pixel dino running"></div>
  </div>

  <div class="opts">
    <b>可选变体（都能做，告诉我选哪个）</b>
    <ol style="line-height:2">
      <li><b>原地跑</b>（当前预览）—— 地面滚动 + 仙人掌掠过，最省资源</li>
      <li><b>横穿屏幕</b> —— 恐龙从右跑到左，跑出画面再回来</li>
      <li><b>完整小循环</b> —— 跑步 + 跳跃避仙人掌 + 摔倒，帧数 ×3</li>
      <li><b>休息快结束</b> —— 最后 1 分钟恐龙打哈欠 / 速度变慢</li>
      <li><b>配色跟随主题</b> —— 白天深色 / 夜间浅色</li>
    </ol>
  </div>

  <div class="note">
    <b>技术落地方式</b><br>
    · 4 张透明 PNG 放进 <code>res/drawable-nodpi/dino_00..03.png</code>（22×19 原始像素）<br>
    · <code>&lt;animated-list&gt;</code> 合成 <code>dino_run.xml</code>，Android 原生支持，无需自定义渲染<br>
    · 锁屏 Activity 里一个 <code>ImageView</code> 调 <code>start()</code> 即可<br>
    · 休息结束时 <code>stop()</code>，不浪费电<br>
    · 素材总共约 <b>2 KB</b>（4 张 22×19 PNG）
  </div>
</div>
</body>
</html>
"""


if __name__ == "__main__":
    build()
