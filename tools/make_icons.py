#!/usr/bin/env python3
"""Generate the extension's PNG icons.

Written by hand with zlib rather than pulled from an image library: the icons
are simple geometry, and this keeps the repo free of build-time dependencies.
Run it after changing the colours; the output is committed.
"""

from __future__ import annotations

import struct
import zlib
from pathlib import Path

ON_COLOR = (47, 111, 79)  # --accent from popup.css
OFF_COLOR = (110, 118, 128)
MARK_COLOR = (255, 255, 255)

SIZES = (16, 32, 48, 128)
SUPERSAMPLE = 4  # cheap antialiasing: render big, average down


def _in_rounded_rect(x: float, y: float, size: float, radius: float) -> bool:
    inset = size * 0.04
    left, top = inset, inset
    right, bottom = size - inset, size - inset
    if not (left <= x <= right and top <= y <= bottom):
        return False
    cx = min(max(x, left + radius), right - radius)
    cy = min(max(y, top + radius), bottom - radius)
    return (x - cx) ** 2 + (y - cy) ** 2 <= radius**2


def _in_diamond(x: float, y: float, size: float, scale: float) -> bool:
    """A centred diamond (the ◈ in the popup header), as |dx|+|dy| <= r."""
    cx = cy = size / 2
    return abs(x - cx) + abs(y - cy) <= size * scale


def _pixel(x: float, y: float, size: float, base: tuple[int, int, int]):
    radius = size * 0.22
    if not _in_rounded_rect(x, y, size, radius):
        return (0, 0, 0, 0)
    # Outer diamond in white, with a smaller one punched back out of it.
    if _in_diamond(x, y, size, 0.30) and not _in_diamond(x, y, size, 0.15):
        return (*MARK_COLOR, 255)
    return (*base, 255)


def render(size: int, base: tuple[int, int, int]) -> bytes:
    """Render one icon and return the PNG bytes."""
    rows = bytearray()
    step = 1.0 / SUPERSAMPLE
    for py in range(size):
        rows.append(0)  # PNG filter type 0 for this scanline
        for px in range(size):
            r = g = b = a = 0
            for sy in range(SUPERSAMPLE):
                for sx in range(SUPERSAMPLE):
                    sample = _pixel(
                        px + (sx + 0.5) * step,
                        py + (sy + 0.5) * step,
                        float(size),
                        base,
                    )
                    r += sample[0] * sample[3]
                    g += sample[1] * sample[3]
                    b += sample[2] * sample[3]
                    a += sample[3]
            samples = SUPERSAMPLE * SUPERSAMPLE
            if a:
                # Weight colour by coverage so edges do not darken.
                rows += bytes((round(r / a), round(g / a), round(b / a), round(a / samples)))
            else:
                rows += b"\x00\x00\x00\x00"
    return _png(size, size, bytes(rows))


def _png(width: int, height: int, raw: bytes) -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        body = tag + data
        return struct.pack("!I", len(data)) + body + struct.pack("!I", zlib.crc32(body))

    header = struct.pack("!IIBBBBB", width, height, 8, 6, 0, 0, 0)  # 8-bit RGBA
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


#: Android launcher icon densities: mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi.
LAUNCHER_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

#: Notification icons are drawn as a mask: only the alpha channel survives, so
#: they must be a white shape on transparency rather than the coloured badge.
STATUS_SIZES = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}


def render_status_icon(size: int) -> bytes:
    """A white diamond on transparency, for the status bar."""
    rows = bytearray()
    step = 1.0 / SUPERSAMPLE
    samples = SUPERSAMPLE * SUPERSAMPLE
    for py in range(size):
        rows.append(0)
        for px in range(size):
            covered = 0
            for sy in range(SUPERSAMPLE):
                for sx in range(SUPERSAMPLE):
                    x = px + (sx + 0.5) * step
                    y = py + (sy + 0.5) * step
                    if _in_diamond(x, y, size, 0.42) and not _in_diamond(x, y, size, 0.21):
                        covered += 1
            alpha = round(255 * covered / samples)
            rows += bytes((255, 255, 255, alpha))
    return _png(size, size, bytes(rows))


def write_android_icons(root: Path) -> int:
    """Write launcher and status-bar icons into the Android res/ tree."""
    written = 0
    for density, size in LAUNCHER_SIZES.items():
        out_dir = root / "android" / "res" / f"mipmap-{density}"
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "ic_launcher.png").write_bytes(render(size, ON_COLOR))
        written += 1
    for density, size in STATUS_SIZES.items():
        out_dir = root / "android" / "res" / f"drawable-{density}"
        out_dir.mkdir(parents=True, exist_ok=True)
        (out_dir / "ic_stat_shield.png").write_bytes(render_status_icon(size))
        written += 1
    return written


def main() -> None:
    root = Path(__file__).resolve().parent.parent
    out_dir = root / "extension" / "icons"
    out_dir.mkdir(parents=True, exist_ok=True)
    for size in SIZES:
        (out_dir / f"icon{size}.png").write_bytes(render(size, ON_COLOR))
    for size in (16, 32):
        (out_dir / f"icon{size}-off.png").write_bytes(render(size, OFF_COLOR))
    print(f"wrote {len(SIZES) + 2} icons to {out_dir}")

    count = write_android_icons(root)
    print(f"wrote {count} icons to {root / 'android' / 'res'}")


if __name__ == "__main__":
    main()
