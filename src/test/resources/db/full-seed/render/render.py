"""Draws the pictures the full seed does not download: the shops' own photos, close-ups of the
official scans, and SAMPLE documents (transfer slips, parcel receipts, payout slips, bank books,
return photos, profile pictures).

Runs in a throwaway python container (scripts/seed-full.sh and seed-full.ps1 start it). Reads the
manifest (render-manifest.sql output) on stdin, skips every key already in the bucket, downloads
the scans it starts from and uploads what it drew.

    MINIO_URL=pegasus-tcg-minio:9000 MINIO_USER=... MINIO_PASSWORD=... BUCKET=pegasus python render.py < manifest

Everything drawn is plainly a mock-up: documents carry SAMPLE watermarks and a "not a real
document" footer, slabs carry DEMO on the label.
"""
import io, json, os, random, sys, hashlib, threading
from concurrent.futures import ThreadPoolExecutor
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageOps, ImageEnhance, features
from minio import Minio

HERE = os.path.dirname(os.path.abspath(__file__))
FONT = os.path.join(HERE, 'fonts', 'Sarabun-Regular.ttf')
FONT_B = os.path.join(HERE, 'fonts', 'Sarabun-Bold.ttf')
LAYOUT = ImageFont.Layout.RAQM if features.check('raqm') else ImageFont.Layout.BASIC
_fonts = {}


def font(size, bold=False):
    k = (size, bold)
    if k not in _fonts:
        _fonts[k] = ImageFont.truetype(FONT_B if bold else FONT, size, layout_engine=LAYOUT)
    return _fonts[k]


def rng(key):
    return random.Random(int(hashlib.sha1(key.encode()).hexdigest()[:12], 16))


# ---------------------------------------------------------------- photo helpers

def noise_bg(size, base, spread, r, blur=1.2):
    w, h = size
    small = Image.new('RGB', (w // 4, h // 4))
    px = small.load()
    for y in range(h // 4):
        for x in range(w // 4):
            d = r.randint(-spread, spread)
            px[x, y] = tuple(max(0, min(255, c + d)) for c in base)
    return small.resize(size, Image.BICUBIC).filter(ImageFilter.GaussianBlur(blur))


def vignette(img, strength=0.55):
    w, h = img.size
    grad = Image.radial_gradient('L').resize((w, h))
    dark = Image.new('RGB', (w, h), (0, 0, 0))
    return Image.composite(dark, img, grad.point(lambda v: int(v * strength)))


def rounded(card, radius_ratio=0.045):
    w, h = card.size
    mask = Image.new('L', (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=int(w * radius_ratio), fill=255)
    out = card.convert('RGBA')
    out.putalpha(mask)
    return out


def paste_shadowed(bg, obj, center, angle, shadow=(18, 22), blur=18, opacity=150):
    obj = obj.rotate(angle, expand=True, resample=Image.BICUBIC)
    sh = Image.new('RGBA', obj.size, (0, 0, 0, 0))
    alpha = obj.split()[-1].point(lambda v: v * opacity // 255)
    sh.putalpha(alpha)
    sh = sh.filter(ImageFilter.GaussianBlur(blur))
    x = int(center[0] - obj.width / 2)
    y = int(center[1] - obj.height / 2)
    bg.alpha_composite(sh, (x + shadow[0], y + shadow[1]))
    bg.alpha_composite(obj, (x, y))
    return bg


def gloss(card, r, strength=70):
    """A soft diagonal light reflection across the card."""
    w, h = card.size
    band = Image.new('L', (w, h), 0)
    d = ImageDraw.Draw(band)
    off = r.uniform(-0.3, 0.3) * w
    d.polygon([(off + w * 0.15, 0), (off + w * 0.45, 0), (off + w * 0.05, h), (off - w * 0.25, h)], fill=strength)
    band = band.filter(ImageFilter.GaussianBlur(w * 0.06))
    white = Image.new('RGBA', (w, h), (255, 255, 255, 0))
    white.putalpha(Image.composite(band, Image.new('L', (w, h), 0), card.split()[-1]))
    out = card.copy()
    out.alpha_composite(white)
    return out


def fit_card(src, height):
    src = src.convert('RGB')
    ratio = src.width / src.height
    if ratio > 0.8:  # tcgplayer scans come square with white padding: trim it
        src = trim_padding(src)
        ratio = src.width / src.height
    return src.resize((int(height * ratio), height), Image.LANCZOS)


def trim_padding(img):
    g = ImageOps.invert(img.convert('L'))
    box = g.point(lambda v: 255 if v > 18 else 0).getbbox()
    return img.crop(box) if box else img


def to_jpeg(img, q=86):
    buf = io.BytesIO()
    img.convert('RGB').save(buf, 'JPEG', quality=q, optimize=True, progressive=True)
    return buf.getvalue(), 'image/jpeg'


def to_png(img):
    buf = io.BytesIO()
    img.convert('RGB').save(buf, 'PNG', optimize=True)
    return buf.getvalue(), 'image/png'


# ---------------------------------------------------------------- catalogue views

def op_art(src, d, r):
    s = trim_padding(src.convert('RGB'))
    w, h = s.size
    if w / h > 0.85:  # sealed product or landscape: centre crop
        crop = s.crop((int(w * 0.15), int(h * 0.15), int(w * 0.85), int(h * 0.85)))
    else:
        crop = s.crop((int(w * 0.07), int(h * 0.09), int(w * 0.93), int(h * 0.55)))
    crop = crop.resize((1000, int(1000 * crop.height / crop.width)), Image.LANCZOS)
    crop = ImageEnhance.Sharpness(crop).enhance(1.25)
    canvas = Image.new('RGB', (1000, 1000), (14, 16, 24))
    canvas.paste(crop, (0, (1000 - crop.height) // 2))
    return to_jpeg(canvas)


def op_detail(src, d, r):
    s = trim_padding(src.convert('RGB'))
    w, h = s.size
    side = int(min(w, h) * 0.55)
    cx, cy = w // 2 + int(w * r.uniform(-0.12, 0.12)), h // 2 + int(h * r.uniform(-0.15, 0.1))
    box = (max(0, cx - side // 2), max(0, cy - side // 2), min(w, cx + side // 2), min(h, cy + side // 2))
    crop = s.crop(box).resize((1000, 1000), Image.LANCZOS)
    return to_jpeg(ImageEnhance.Sharpness(crop).enhance(1.2))


def op_angle(src, d, r):
    bg = Image.new('RGBA', (1000, 1000))
    top, bottom = (36, 40, 58), (10, 11, 16)
    dr = ImageDraw.Draw(bg)
    for y in range(1000):
        t = y / 999
        dr.line([(0, y), (999, y)], fill=tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3)) + (255,))
    card = fit_card(src, 760)
    if card.width > 900: card = card.resize((900, int(900 * card.height / card.width)))
    card = gloss(rounded(card, 0.035 if card.width / card.height < 0.8 else 0.01), r, 90)
    # a gentle perspective: narrower at the far edge
    w, h = card.size
    k = r.uniform(0.05, 0.09)
    coeffs = _coeffs([(0, 0), (w, h * k), (w, h * (1 - k)), (0, h)], [(0, 0), (w, 0), (w, h), (0, h)])
    card = card.transform((w, h), Image.PERSPECTIVE, coeffs, Image.BICUBIC)
    paste_shadowed(bg, card, (500, 480), r.uniform(-4, 4), shadow=(26, 34), blur=26, opacity=170)
    return to_jpeg(bg)


def _coeffs(pa, pb):
    """Solve the 8 perspective coefficients without numpy."""
    m = []
    for (x, y), (X, Y) in zip(pa, pb):
        m.append([x, y, 1, 0, 0, 0, -X * x, -X * y, X])
        m.append([0, 0, 0, x, y, 1, -Y * x, -Y * y, Y])
    n = 8
    for c in range(n):
        p = max(range(c, n), key=lambda i: abs(m[i][c]))
        m[c], m[p] = m[p], m[c]
        for i in range(n):
            if i != c and m[c][c]:
                f = m[i][c] / m[c][c]
                for j in range(c, n + 1):
                    m[i][j] -= f * m[c][j]
    return [m[i][n] / m[i][i] for i in range(n)]


find_coeffs = _coeffs


# ---------------------------------------------------------------- the shops' photos

MATS = [(18, 52, 40), (20, 30, 60), (44, 24, 52), (34, 34, 38), (60, 26, 26)]
WOOD = [(150, 112, 78), (122, 88, 60), (176, 140, 102), (98, 72, 52)]


def playmat(r, size=(1000, 1000)):
    base = r.choice(MATS)
    bg = noise_bg(size, base, 10, r).convert('RGBA')
    d = ImageDraw.Draw(bg)
    # stitched edge of the mat in one corner
    m = r.randint(40, 90)
    d.rounded_rectangle([m, m, size[0] - m, size[1] - m], radius=40, outline=tuple(min(255, c + 35) for c in base) + (255,), width=3)
    return bg


def wood(r, size=(1000, 1000)):
    base = r.choice(WOOD)
    img = noise_bg(size, base, 14, r, 0.8)
    d = ImageDraw.Draw(img)
    for i in range(0, size[1], r.randint(18, 30)):
        c = tuple(max(0, v - r.randint(10, 26)) for v in base)
        d.line([(0, i + r.randint(-3, 3)), (size[0], i + r.randint(-6, 6))], fill=c, width=r.randint(1, 3))
    return img.filter(ImageFilter.GaussianBlur(1)).convert('RGBA')


def op_mat(src, d, r):
    bg = playmat(r)
    card = gloss(rounded(fit_card(src, r.randint(700, 760))), r, 45)
    paste_shadowed(bg, card, (500 + r.randint(-30, 30), 500 + r.randint(-20, 20)), r.uniform(-7, 7))
    return to_jpeg(vignette(bg.convert('RGB'), 0.45))


def op_toploader(src, d, r):
    bg = Image.new('RGBA', (1000, 1000), (236, 233, 228, 255))
    bg = Image.alpha_composite(bg, noise_bg((1000, 1000), (232, 229, 224), 6, r).convert('RGBA'))
    card = rounded(fit_card(src, 660))
    w, h = card.size
    tl = Image.new('RGBA', (w + 60, h + 70), (0, 0, 0, 0))
    dt = ImageDraw.Draw(tl)
    dt.rounded_rectangle([0, 0, w + 59, h + 69], radius=14, fill=(255, 255, 255, 60), outline=(255, 255, 255, 190), width=4)
    dt.rounded_rectangle([(w + 60) / 2 - 60, -6, (w + 60) / 2 + 60, 14], radius=8, fill=(236, 233, 228, 255))
    tl.alpha_composite(card, (30, 45))
    tl = gloss(tl, r, 70)
    paste_shadowed(bg, tl, (500 + r.randint(-25, 25), 500), r.uniform(-9, 9), shadow=(14, 18), blur=14, opacity=110)
    return to_jpeg(vignette(bg.convert('RGB'), 0.25))


def op_corner(src, d, r):
    s = fit_card(src, 2000)
    w, h = s.size
    corner = r.choice(['tl', 'tr', 'bl', 'br'])
    cw, ch = int(w * 0.42), int(w * 0.42)
    x0 = 0 if corner[1] == 'l' else w - cw
    y0 = 0 if corner[0] == 't' else h - ch
    crop = rounded(s, 0.045).crop((x0, y0, x0 + cw, y0 + ch)).resize((820, 820), Image.LANCZOS)
    bg = playmat(r)
    off = (90 if corner[1] == 'l' else 90, 90 if corner[0] == 't' else 90)
    bg.alpha_composite(crop, off)
    out = bg.convert('RGB').filter(ImageFilter.UnsharpMask(2, 60))
    dr = ImageDraw.Draw(out)
    dr.rounded_rectangle([24, 24, 300, 74], radius=12, fill=(0, 0, 0))
    dr.text((40, 30), 'ภาพขยายมุมการ์ด', font=font(30, True), fill=(255, 255, 255))
    return to_jpeg(out)


def op_table(src, d, r):
    bg = wood(r)
    s = trim_padding(src.convert('RGB'))
    scale = 760 / max(s.size)
    obj = s.resize((int(s.width * scale), int(s.height * scale)), Image.LANCZOS).convert('RGBA')
    obj = gloss(obj, r, 35)
    paste_shadowed(bg, obj, (500, 520), r.uniform(-5, 5), shadow=(20, 26), blur=22, opacity=160)
    return to_jpeg(vignette(bg.convert('RGB'), 0.4))


GRADE_WORDS = {10: 'GEM MINT', 9.5: 'GEM MINT', 9: 'MINT', 8.5: 'NM-MT+', 8: 'NM-MT', 7: 'NEAR MINT'}
GRADER_COLORS = {'PSA': ((196, 30, 40), (255, 255, 255)), 'BGS': ((24, 24, 28), (212, 175, 55)),
                 'CGC': ((14, 60, 130), (255, 255, 255))}


def op_slab(src, d, r):
    bg = Image.new('RGBA', (1000, 1000), (238, 238, 240, 255))
    bg = Image.alpha_composite(bg, noise_bg((1000, 1000), (232, 232, 236), 5, r).convert('RGBA'))
    card = rounded(fit_card(src, 600))
    cw, chh = card.size
    sw, sh = cw + 110, chh + 300
    slab = Image.new('RGBA', (sw, sh), (0, 0, 0, 0))
    ds = ImageDraw.Draw(slab)
    ds.rounded_rectangle([0, 0, sw - 1, sh - 1], radius=26, fill=(250, 250, 252, 110), outline=(200, 204, 212, 255), width=6)
    grader = (d.get('grader') or 'PSA').upper()
    accent, ink = GRADER_COLORS.get(grader, GRADER_COLORS['PSA'])
    # label
    lx0, ly0, lx1, ly1 = 30, 30, sw - 30, 200
    ds.rounded_rectangle([lx0, ly0, lx1, ly1], radius=10, fill=(255, 255, 255, 255), outline=accent + (255,), width=5)
    ds.rectangle([lx0 + 5, ly0 + 5, lx0 + 120, ly1 - 5], fill=accent + (255,))
    ds.text((lx0 + 62, ly0 + 56), grader, font=font(40, True), fill=ink, anchor='mm')
    g = float(d.get('grade') or 10)
    gtxt = f'{g:g}'
    ds.text((lx0 + 62, ly0 + 118), gtxt, font=font(54, True), fill=ink, anchor='mm')
    name = (d.get('name') or '')[:34]
    year = d.get('year') or ''
    setn = (d.get('set') or '')[:30]
    num = d.get('number') or ''
    ds.text((lx0 + 140, ly0 + 18), f'{year} {setn}'.strip()[:38], font=font(24, True), fill=(30, 30, 36))
    ds.text((lx0 + 140, ly0 + 52), name.upper(), font=font(28, True), fill=(10, 10, 14))
    ds.text((lx0 + 140, ly0 + 92), f'#{num}', font=font(24), fill=(60, 60, 70))
    ds.text((lx1 - 16, ly0 + 92), GRADE_WORDS.get(g, 'NM-MT'), font=font(26, True), fill=accent, anchor='ra')
    cert = (d.get('cert') or '').split(' ')[-1]
    ds.text((lx0 + 140, ly0 + 128), f'CERT {cert}', font=font(20), fill=(90, 90, 100))
    ds.text((lx1 - 16, ly0 + 132), 'DEMO', font=font(18, True), fill=(170, 170, 180), anchor='ra')
    # card window
    ds.rounded_rectangle([40, 230, sw - 40, sh - 40], radius=14, outline=(214, 218, 226, 255), width=4)
    slab.alpha_composite(card, ((sw - cw) // 2, 250))
    slab = gloss(slab, r, 80)
    scale = 900 / sh
    slab = slab.resize((int(sw * scale), int(sh * scale)), Image.LANCZOS)
    paste_shadowed(bg, slab, (500, 500), r.uniform(-3, 3), shadow=(16, 20), blur=18, opacity=120)
    return to_jpeg(vignette(bg.convert('RGB'), 0.2))


# ---------------------------------------------------------------- SAMPLE documents

def watermark(img, text='SAMPLE • ตัวอย่าง', alpha=70):
    w, h = img.size
    layer = Image.new('RGBA', (w * 2, h * 2), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    f = font(max(40, w // 9), True)
    step = max(160, h // 4)
    for i, y in enumerate(range(0, h * 2, step)):
        d.text(((i % 2) * w // 3 - w // 4, y), (text + '   ') * 3, font=f, fill=(222, 70, 70, alpha))
    layer = layer.rotate(28, resample=Image.BICUBIC).crop((w // 2, h // 2, w // 2 + w, h // 2 + h))
    out = img.convert('RGBA')
    out.alpha_composite(layer)
    return out


def footer(img, text='ภาพจำลองสำหรับข้อมูลทดสอบ ไม่ใช่เอกสารจริง'):
    w, h = img.size
    d = ImageDraw.Draw(img)
    d.rectangle([0, h - 30, w, h], fill=(253, 236, 236))
    d.text((w // 2, h - 15), text, font=font(15), fill=(180, 30, 30), anchor='mm')
    return img


BANK = {'KBANK': ('K PLUS', (19, 138, 54)), 'SCB': ('SCB EASY', (78, 45, 127)), 'BBL': ('Bangkok Bank', (30, 66, 158)),
        'KTB': ('Krungthai NEXT', (0, 165, 224)), 'BAY': ('KMA', (254, 196, 0)), 'TTB': ('TTB Mobile', (0, 80, 240)),
        'GSB': ('MyMo', (235, 25, 140)), 'CIMBT': ('CIMB THAI', (126, 37, 37)), 'UOBT': ('UOB TMRW', (0, 51, 102)),
        'KKP': ('KKP Mobile', (91, 54, 120)), 'TISCO': ('TISCO', (12, 70, 140))}
BANK_TH = {'KBANK': 'ธนาคารกสิกรไทย', 'SCB': 'ธนาคารไทยพาณิชย์', 'BBL': 'ธนาคารกรุงเทพ', 'KTB': 'ธนาคารกรุงไทย',
           'BAY': 'ธนาคารกรุงศรีอยุธยา', 'TTB': 'ธนาคารทหารไทยธนชาต', 'GSB': 'ธนาคารออมสิน'}


def baht(v):
    return f'{float(v):,.2f} บาท'


def rows(d, items, x0, y0, gap=56, label_w=110, size=26):
    for i, (k, v) in enumerate(items):
        y = y0 + i * gap
        d.text((x0, y), k, font=font(22), fill=(110, 112, 120))
        d.text((x0 + label_w, y - 4), str(v), font=font(size, True), fill=(20, 22, 30))


def op_slip(d, r):
    code = d.get('bank') or 'KBANK'
    app, color = BANK.get(code, BANK['KBANK'])
    img = Image.new('RGB', (480, 820), (255, 255, 255))
    dr = ImageDraw.Draw(img)
    dr.rectangle([0, 0, 480, 90], fill=color)
    dr.text((24, 45), f'{app} (ตัวอย่าง)', font=font(30, True), fill=(255, 255, 255) if code != 'BAY' else (60, 40, 0), anchor='lm')
    dr.ellipse([205, 120, 275, 190], fill=(24, 160, 72))
    dr.line([(222, 156), (236, 170), (260, 140)], fill=(255, 255, 255), width=8)
    dr.text((240, 230), 'โอนเงินสำเร็จ', font=font(34, True), fill=(24, 150, 70), anchor='mm')
    dr.text((240, 268), d.get('at') or '', font=font(20), fill=(120, 120, 130), anchor='mm')
    rows(dr, [('จาก', (d.get('payer') or '')[:22]), ('บัญชี', f"xxx-x-x{d.get('acct', '0000')}-x"),
              ('ไปยัง', 'PEGASUS TCG'), ('ธนาคาร', 'TEST BANK 000-0-00000-0')], 32, 300)
    dr.line([(32, 530), (448, 530)], fill=(225, 225, 230), width=2)
    dr.text((32, 560), 'จำนวนเงิน', font=font(22), fill=(110, 112, 120))
    dr.text((448, 552), baht(d.get('amount', 0)), font=font(32, True), fill=(15, 15, 22), anchor='ra')
    rows(dr, [('ค่าธรรมเนียม', '0.00 บาท'), ('เลขที่รายการ', d.get('ref') or '-'), ('บันทึกช่วยจำ', (d.get('order') or '')[-16:])],
         32, 620, gap=50, label_w=140, size=22)
    return to_png(footer(watermark(img)))


CARRIER = {'Kerry Express': (255, 117, 24), 'Flash Express': (255, 214, 0), 'ไปรษณีย์ไทย': (220, 30, 40),
           'J&T Express': (225, 30, 40), 'Lalamove ส่งด่วนในกรุงเทพ': (240, 100, 20)}


def op_parcel(d, r):
    carrier = d.get('carrier') or 'Kerry Express'
    color = CARRIER.get(carrier, (90, 90, 100))
    img = Image.new('RGB', (720, 540), (255, 252, 245))
    dr = ImageDraw.Draw(img)
    dr.rectangle([0, 0, 720, 80], fill=color)
    dr.text((28, 40), f'{carrier} • ใบรับฝากพัสดุ', font=font(30, True), fill=(20, 20, 30), anchor='lm')
    dr.text((28, 118), 'หมายเลขพัสดุ', font=font(20), fill=(110, 110, 120))
    dr.text((28, 140), d.get('tracking') or '-', font=font(44, True), fill=(16, 18, 30))
    x = 28
    rr = rng(d.get('tracking') or 'x')
    while x < 400:
        wbar = rr.choice([2, 3, 5, 7])
        dr.rectangle([x, 205, x + wbar, 275], fill=(16, 18, 30))
        x += wbar + rr.choice([3, 5, 7])
    rows(dr, [('ผู้ส่ง', (d.get('sender') or '')[:30]),
              ('ผู้รับ', f"{(d.get('recipient') or '')[:20]} ({d.get('province') or ''})"),
              ('ประเภท', 'การ์ดสะสม / กล่องพัสดุขนาดเล็ก')], 28, 305, gap=52, label_w=110, size=24)
    return to_png(footer(watermark(img)))


def op_payout(d, r):
    img = Image.new('RGB', (480, 760), (255, 255, 255))
    dr = ImageDraw.Draw(img)
    dr.rectangle([0, 0, 480, 90], fill=(15, 23, 42))
    dr.text((24, 45), 'PEGASUS TCG • โอนเงินให้ผู้ขาย', font=font(26, True), fill=(255, 255, 255), anchor='lm')
    dr.text((240, 150), 'โอนเงินสำเร็จ', font=font(34, True), fill=(24, 150, 70), anchor='mm')
    acct = d.get('acct') or '0000000000'
    rows(dr, [('จาก', 'บริษัท เพกาซัส ทีซีจี จำกัด'), ('ไปยัง', (d.get('name') or '')[:24]),
              ('ธนาคาร', (d.get('bank') or '')[:22]), ('เลขบัญชี', f'xxx-x-x{acct[-5:-1]}-{acct[-1]}')], 32, 200)
    dr.line([(32, 430), (448, 430)], fill=(225, 225, 230), width=2)
    dr.text((32, 460), 'จำนวนเงิน', font=font(22), fill=(110, 112, 120))
    dr.text((448, 452), baht(d.get('amount', 0)), font=font(34, True), fill=(15, 15, 22), anchor='ra')
    rows(dr, [('เลขอ้างอิง', d.get('ref') or '-')], 32, 520, label_w=130, size=24)
    dr.rectangle([32, 580, 448, 588], fill=(80, 46, 130))
    return to_png(footer(watermark(img)))


def op_bankbook(d, r):
    code = d.get('code') or 'KBANK'
    color = BANK.get(code, BANK['KBANK'])[1]
    img = Image.new('RGB', (900, 560), (246, 243, 236))
    dr = ImageDraw.Draw(img)
    dr.rectangle([0, 0, 900, 110], fill=color)
    dr.text((36, 55), d.get('bank') or BANK_TH.get(code, ''), font=font(42, True), fill=(255, 255, 255), anchor='lm')
    dr.text((36, 150), 'สมุดบัญชีเงินฝากออมทรัพย์', font=font(32, True), fill=(30, 32, 44))
    dr.text((36, 205), 'ชื่อบัญชี', font=font(22), fill=(110, 110, 120))
    dr.text((36, 235), d.get('name') or '', font=font(36, True), fill=(16, 18, 28))
    acct = d.get('acct') or '0000000000'
    dr.text((36, 305), 'เลขที่บัญชี', font=font(22), fill=(110, 110, 120))
    dr.text((36, 335), f'{acct[:3]}-{acct[3:4]}-{acct[4:9]}-{acct[9:]}', font=font(44, True), fill=(16, 18, 28))
    dr.text((36, 415), 'สาขา  สำนักงานตัวอย่าง', font=font(22), fill=(80, 80, 90))
    dr.rounded_rectangle([640, 150, 860, 420], radius=20, outline=color, width=4)
    dr.text((750, 255), 'ไม่ใช่', font=font(32, True), fill=color, anchor='mm')
    dr.text((750, 305), 'สมุดจริง', font=font(32, True), fill=color, anchor='mm')
    return to_png(footer(watermark(img)))


def op_avatar(d, r):
    rr = rng(d.get('seed') or 'x')
    palette = [(79, 70, 229), (14, 165, 233), (16, 185, 129), (245, 158, 11), (239, 68, 68), (168, 85, 247),
               (236, 72, 153), (20, 184, 166), (100, 116, 139), (234, 88, 12)]
    img = Image.new('RGB', (256, 256), rr.choice(palette))
    name = (d.get('name') or '?').replace('.', ' ').split()
    initials = ''.join(w[0] for w in name[:2]).upper() if name and name[0].isascii() else (d.get('name') or '?')[:1]
    ImageDraw.Draw(img).text((128, 128), initials, font=font(96, True), fill=(255, 255, 255), anchor='mm')
    return to_png(img)


REASON_TH = {'DAMAGED': 'เสียหาย', 'NOT_AS_DESCRIBED': 'ไม่ตรงตามประกาศ', 'WRONG_ITEM': 'ได้รับของผิด',
             'COUNTERFEIT': 'สงสัยของปลอม', 'OTHER': 'อื่น ๆ'}


def op_evidence(src, d, r):
    bg = wood(r, (720, 720))
    card = rounded(fit_card(src, 470))
    paste_shadowed(bg, card, (360 + r.randint(-30, 30), 380), r.uniform(-12, 12), shadow=(12, 16), blur=12)
    img = bg.convert('RGB')
    dr = ImageDraw.Draw(img)
    cx, cy = r.randint(260, 470), r.randint(260, 520)
    dr.ellipse([cx - 60, cy - 60, cx + 60, cy + 60], outline=(236, 50, 50), width=7)
    dr.rectangle([0, 0, 720, 56], fill=(0, 0, 0))
    dr.text((20, 28), f"รูปหลักฐาน {d.get('n', 1)}/{d.get('of', 2)} • {REASON_TH.get(d.get('reason'), 'อื่น ๆ')}",
            font=font(26, True), fill=(255, 255, 255), anchor='lm')
    return to_png(footer(watermark(img, alpha=55), 'ภาพจำลองสำหรับข้อมูลทดสอบ ไม่ใช่รูปถ่ายจริง'))


PHOTO_OPS = {'art': op_art, 'angle': op_angle, 'detail': op_detail, 'mat': op_mat, 'toploader': op_toploader,
             'corner': op_corner, 'table': op_table, 'slab': op_slab, 'evidence': op_evidence}
DOC_OPS = {'slip': op_slip, 'parcel': op_parcel, 'payout': op_payout, 'bankbook': op_bankbook, 'avatar': op_avatar}


def main():
    client = Minio(os.environ['MINIO_URL'], access_key=os.environ['MINIO_USER'],
                   secret_key=os.environ['MINIO_PASSWORD'], secure=False)
    bucket = os.environ.get('BUCKET', 'pegasus')
    todo = []
    for line in sys.stdin:
        line = line.strip()
        if line.startswith('{'):
            todo.append(json.loads(line))
    def meta(o):
        return {k.lower().replace('x-amz-meta-', ''): v for k, v in (o.metadata or {}).items()}

    # What was drawn, and from which version of its scan (etag kept in its metadata).
    drawn = {}
    for prefix in ('seed/gen/', 'payments/seed/', 'shipments/seed/', 'payouts/seed/', 'returns/seed/',
                   'verifications/seed/', 'avatars/seed/'):
        for o in client.list_objects(bucket, prefix=prefix, recursive=True, include_user_meta=True):
            drawn[o.object_name] = meta(o).get('drawn-from', '')
    # The scans: a downloaded one carries Source-Sha1; a placeholder (download failed) does not.
    scans = {}
    for o in client.list_objects(bucket, prefix='seed/', recursive=True, include_user_meta=True):
        if not o.object_name.startswith('seed/gen/'):
            scans[o.object_name] = (o.etag or '').strip('"') if 'source-sha1' in meta(o) else None
    seen, jobs, waiting = set(), [], 0
    for d in todo:
        k = d.get('key')
        if not k or k in seen: continue
        seen.add(k)
        if d['op'] in PHOTO_OPS:
            etag = scans.get(d.get('src') or '')
            if not etag:  # the scan is missing or a placeholder: draw it on a later run
                waiting += 1
                continue
            d['from'] = etag
            if drawn.get(k) == etag: continue
        elif k in drawn:
            continue
        jobs.append(d)
    print(f'render: {len(todo)} pictures in the data, {len(jobs)} to draw'
          f' ({"raqm" if LAYOUT == ImageFont.Layout.RAQM else "basic"} text)', flush=True)
    if waiting:
        print(f'render: {waiting} wait for scans that did not download; run the seed again', flush=True)
    cache = {}
    lock = threading.Lock()

    def source(key):
        # Cache the bytes, not the image: PIL images are not safe to decode from several threads.
        with lock:
            data = cache.get(key)
        if data is None:
            resp = client.get_object(bucket, key)
            try:
                data = resp.read()
            finally:
                resp.close(); resp.release_conn()
            with lock:
                cache[key] = data
        img = Image.open(io.BytesIO(data))
        img.load()
        return img

    def work(d):
        r = rng(d['key'])
        op = d['op']
        try:
            if op in PHOTO_OPS:
                if not d.get('src'): return 'skip'
                data, ctype = PHOTO_OPS[op](source(d['src']), d, r)
            elif op in DOC_OPS:
                data, ctype = DOC_OPS[op](d, r)
            else:
                return 'skip'
            client.put_object(bucket, d['key'], io.BytesIO(data), len(data), content_type=ctype,
                              metadata={'drawn-from': d['from']} if d.get('from') else None)
            return 'ok'
        except Exception as e:  # one bad picture must not stop the rest
            print(f'  could not draw {d["key"]}: {e}', flush=True)
            return 'fail'

    done = fail = 0
    # sources are shared, so keep jobs of one scan together
    jobs.sort(key=lambda d: d.get('src') or '')
    with ThreadPoolExecutor(max_workers=int(os.environ.get('WORKERS', '6'))) as pool:
        for i, res in enumerate(pool.map(work, jobs), 1):
            done += res == 'ok'; fail += res == 'fail'
            if i % 500 == 0:
                print(f'  {i}/{len(jobs)}', flush=True)
                with lock:
                    if len(cache) > 400: cache.clear()
    print(f'render: {done} drawn, {fail} failed', flush=True)
    sys.exit(1 if fail or waiting else 0)


if __name__ == '__main__':
    main()
