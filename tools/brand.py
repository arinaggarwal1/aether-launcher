"""Generate Aether Launcher branding assets from the approved logo.

Usage: python3 -I tools/brand.py <repo root>  (requires Pillow)
The logo source is docs/images/aether-logo.png; changing it is a separate design decision.
"""
import base64
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(sys.argv[1])
SOURCE = ROOT / 'docs/images/aether-logo.png'
FONTS = ROOT / 'assets/fonts'
TOOLS = ROOT / 'tools/fonts'
logo = Image.open(SOURCE).convert('RGB')


def icon(size):
    return logo.resize((size, size), Image.Resampling.LANCZOS)


def banner(width, height):
    scale = 3
    w, h = width * scale, height * scale
    canvas = Image.new('RGB', (w, h), (0, 0, 0))
    size = int(h * .55)
    bold = ImageFont.truetype(str(FONTS / 'InterDisplay-Bold.ttf'), int(h * .115))
    thin = ImageFont.truetype(str(TOOLS / 'InterDisplay-Thin.ttf'), int(h * .115))
    first, second = 'Aether', ' Launcher'
    a = bold.getlength(first)
    b = thin.getlength(second)
    gap = int(h * .04)
    x = int((w - size - gap - a - b) / 2)
    canvas.paste(icon(size), (x, (h - size) // 2))
    draw = ImageDraw.Draw(canvas)
    base = h * .5 + h * .042
    draw.text((x + size + gap, base), first, font=bold, fill='white', anchor='ls')
    draw.text((x + size + gap + a, base), second, font=thin, fill='white', anchor='ls')
    return canvas.resize((width, height), Image.Resampling.LANCZOS)


if __name__ == '__main__':
    icon(192).save(ROOT / 'res/drawable-xxxhdpi/ic_launcher.png', optimize=True)
    icon(256).save(ROOT / 'res/drawable-nodpi/refract_mark.png', optimize=True)
    banner(640, 360).save(ROOT / 'res/drawable-xxxhdpi/banner.png', optimize=True)
    icon(1024).save(ROOT / 'docs/images/refract-icon.png', optimize=True)
    banner(1280, 720).save(ROOT / 'docs/images/refract-banner.png', optimize=True)
    icon(256).save(ROOT / 'assets/present/logo.png', optimize=True)
    # Keep the existing public /icon.svg URL usable for presentation clients and bookmarks.
    encoded = base64.b64encode((ROOT / 'assets/present/logo.png').read_bytes()).decode('ascii')
    svg = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="256" height="256" viewBox="0 0 256 256"><image width="256" height="256" xlink:href="data:image/png;base64,' + encoded + '"/></svg>\n'
    (ROOT / 'assets/present/icon.svg').write_text(svg)
    print('Generated Aether branding assets')
