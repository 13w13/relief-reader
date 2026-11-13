#!/usr/bin/env python3
"""Generates the test PDFs of the PDF text view (T9). Python standard library only.

    python3 tools/make_test_pdfs.py

The files go to app/src/debug/assets/test-pdf/: the debug build seeds them for the Maestro
flow (relaispdf://sample/<name>), and the unit tests read them from there. The content is
invented for the tests ("Northland" is a fictional region): no text of a real report.

- sitrep-two-columns.pdf  4 pages: title across the page, then two columns, a table drawn
                          with lines (page 2), a page that is mostly an image (page 3),
                          running header and page numbers on every page. Pages 2 and 4
                          write their lines row by row across the two columns, so the
                          order of the file is not the reading order there.
- scan-no-text.pdf        2 pages of images only, like a scan without a text layer.
- long-25-pages.pdf       25 pages of two-column text, for timing the extraction.
- french-one-column.pdf   1 page, one column, accents and a word cut by a hyphen.
- test-sitrep.pdf         produit/passe-v0.2.md 7.1, for journey 7: 3 pages, two columns
                          written row by row, a table of 4 columns on page 2, "trucking"
                          twice (pages 1 and 3).
- test-scan.pdf           produit/passe-v0.2.md 7.1: 2 pages of images, no text.

The output is deterministic: running the script twice gives the same bytes.
"""
import os
import random
import zlib

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "debug", "assets", "test-pdf")

A4 = (595, 842)

# Helvetica widths (Adobe AFM, 1/1000 em) for printable ASCII; other characters use 556.
_W = (
    "278 278 355 556 556 889 667 191 333 333 389 584 278 333 278 278 556 556 556 556 556 556 556 556 556 556 "
    "278 278 584 584 584 556 1015 667 667 722 722 667 611 778 722 278 500 667 556 833 722 778 667 778 722 667 "
    "611 722 667 944 667 667 611 278 278 278 469 556 333 556 556 500 556 556 278 556 556 222 222 500 222 833 "
    "556 556 556 556 333 500 278 556 500 722 500 500 500 334 260 334 584"
).split()
WIDTHS = {chr(32 + i): int(w) for i, w in enumerate(_W)}


def text_width(text, size, bold=False):
    w = sum(WIDTHS.get(c, 556) for c in text) * size / 1000.0
    return w * (1.08 if bold else 1.0)


def wrap(text, width, size, bold=False):
    lines, line = [], ""
    for word in text.split():
        candidate = word if not line else line + " " + word
        if text_width(candidate, size, bold) <= width:
            line = candidate
        else:
            lines.append(line)
            line = word
    if line:
        lines.append(line)
    return lines


def pdf_string(text):
    raw = text.encode("cp1252")
    out = bytearray(b"(")
    for b in raw:
        if b in (0x28, 0x29, 0x5C):
            out += b"\\" + bytes([b])
        elif b < 32 or b > 126:
            out += b"\\%03o" % b
        else:
            out.append(b)
    out += b")"
    return bytes(out)


class Page:
    def __init__(self):
        self.ops = []
        self.images = {}

    def text(self, x, y, text, size=10, bold=False):
        font = b"/F2" if bold else b"/F1"
        self.ops.append(b"BT %s %d Tf 1 0 0 1 %.2f %.2f Tm %s Tj ET" % (font, size, x, y, pdf_string(text)))

    def rect(self, x, y, w, h, fill=None):
        if fill is not None:
            self.ops.append(b"%.3f g %.2f %.2f %.2f %.2f re f 0 g" % (fill, x, y, w, h))
        else:
            self.ops.append(b"0.5 w %.2f %.2f %.2f %.2f re S" % (x, y, w, h))

    def line(self, x1, y1, x2, y2):
        self.ops.append(b"0.5 w %.2f %.2f m %.2f %.2f l S" % (x1, y1, x2, y2))

    def image(self, name, x, y, w, h, pixels, pw, ph):
        self.images[name] = (pixels, pw, ph)
        self.ops.append(b"q %.2f 0 0 %.2f %.2f %.2f cm /%s Do Q" % (w, h, x, y, name.encode()))


class Pdf:
    def __init__(self):
        self.objects = []

    def add(self, body):
        self.objects.append(body)
        return len(self.objects)

    def stream(self, data, extra=b""):
        packed = zlib.compress(data, 9)
        return self.add(b"<< /Length %d /Filter /FlateDecode %s >>\nstream\n" % (len(packed), extra) + packed + b"\nendstream")

    def build(self, pages, title):
        f1 = self.add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        f2 = self.add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        pages_id = len(self.objects) + 1 + 0  # placeholder, fixed below
        self.objects.append(None)
        pages_id = len(self.objects)
        kids = []
        for page in pages:
            xobjects = b""
            for name, (pixels, pw, ph) in sorted(page.images.items()):
                img = self.stream(
                    pixels,
                    b"/Type /XObject /Subtype /Image /Width %d /Height %d /ColorSpace /DeviceGray /BitsPerComponent 8" % (pw, ph),
                )
                xobjects += b"/%s %d 0 R " % (name.encode(), img)
            content = self.stream(b"\n".join(page.ops))
            resources = b"<< /Font << /F1 %d 0 R /F2 %d 0 R >>%s >>" % (
                f1, f2, (b" /XObject << " + xobjects + b">>") if xobjects else b"",
            )
            kids.append(self.add(
                b"<< /Type /Page /Parent %d 0 R /MediaBox [0 0 %d %d] /Resources %s /Contents %d 0 R >>"
                % (pages_id, A4[0], A4[1], resources, content)
            ))
        self.objects[pages_id - 1] = b"<< /Type /Pages /Kids [%s] /Count %d >>" % (
            b" ".join(b"%d 0 R" % k for k in kids), len(kids),
        )
        info = self.add(b"<< /Title %s /Producer (Relais test generator) >>" % pdf_string(title))
        catalog = self.add(b"<< /Type /Catalog /Pages %d 0 R >>" % pages_id)
        out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
        offsets = []
        for i, body in enumerate(self.objects, start=1):
            offsets.append(len(out))
            out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
        xref = len(out)
        out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(self.objects) + 1)
        for off in offsets:
            out += b"%010d 00000 n \n" % off
        out += b"trailer\n<< /Size %d /Root %d 0 R /Info %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (
            len(self.objects) + 1, catalog, info, xref,
        )
        return bytes(out)


def gray_image(w, h, seed, text_like=False):
    rnd = random.Random(seed)
    rows = []
    for y in range(h):
        row = bytearray()
        for x in range(w):
            if text_like:
                # Dark dashes on light paper, in lines: a scanned page without a text layer.
                in_line = (y // 6) % 3 != 2 and 12 < x < w - 12 and 10 < y < h - 10
                v = 40 if in_line and (x // 5 + y // 18 + seed) % 7 != 0 and rnd.random() < 0.8 else 235
            else:
                # A bar chart: bars of different heights over a light background.
                bar = x // (w // 8)
                height = int(h * (0.25 + 0.08 * ((bar * 5 + seed) % 9)))
                v = 90 + 18 * (bar % 3) if (h - y) < height and x % (w // 8) > 6 else 245
            row.append(v)
        rows.append(bytes(row))
    return b"".join(rows)


# --- Content: invented sentences about a fictional region --------------------------------

SENTENCES = [
    "Water trucking reached 12,400 people in the northern camps during the reporting period.",
    "Partners repaired two hand pumps and one borehole in Eastvale district.",
    "The health cluster recorded fewer cases of acute watery diarrhoea than in September.",
    "Mobile clinics visited eleven villages that had been cut off by the floods.",
    "Shelter kits were handed out to families whose homes were damaged in the storm.",
    "Road access to the river crossing remains limited for heavy trucks.",
    "School attendance rose after temporary learning spaces opened in three sites.",
    "Food distributions covered 61 per cent of the planned caseload this month.",
    "Nutrition screening found higher rates of acute malnutrition among children under five.",
    "Protection teams referred 140 people to specialised services.",
    "Prices of staple foods increased in the main market of Northland town.",
    "Rains are expected to continue until the end of November.",
    "Partners call for funding to keep water services running through the dry season.",
    "A joint assessment is planned for the hill villages next week.",
]

HEADINGS = ["Water and sanitation", "Health", "Shelter", "Food security", "Protection", "Access", "Education", "Funding"]


def paragraph(rnd, n=3):
    return " ".join(rnd.choice(SENTENCES) for _ in range(n))


def chrome(page, number, total, title):
    """Running header and page number, the same on every page."""
    page.text(40, 815, "Relais test document · " + title, size=8)
    page.line(40, 808, 555, 808)
    label = "Page %d of %d" % (number, total)
    page.text(297 - text_width(label, 8) / 2, 28, label, size=8)


class Column:
    """Fills a column from the top, paragraph by paragraph. Returns False when it is full."""

    def __init__(self, page, x, top, bottom, width):
        self.page, self.x, self.y, self.bottom, self.width = page, x, top, bottom, width

    def heading(self, text):
        if self.y - 30 < self.bottom:
            return False
        self.y -= 6
        self.page.text(self.x, self.y, text, size=12, bold=True)
        self.y -= 18
        return True

    def para(self, text, size=10, leading=13, gap=7):
        lines = wrap(text, self.width * 0.97, size)
        if self.y - leading * len(lines) < self.bottom:
            return False
        for line in lines:
            self.page.text(self.x, self.y, line, size=size)
            self.y -= leading
        self.y -= gap
        return True


def two_columns(page, rnd, top, bottom, items, interleave=False):
    """items: list of ("h", text) or ("p", text), poured into the left then the right column.

    With interleave, the lines are written to the file row by row across both columns, as
    some PDF writers do: the order of the file is then not the reading order.
    """
    if interleave:
        kept, page.ops = page.ops, []
        rest = two_columns(page, rnd, top, bottom, items)
        rows = sorted(page.ops, key=lambda op: (-float(op.split(b" Tm")[0].split()[-1]), float(op.split(b" Tm")[0].split()[-2])))
        page.ops = kept + rows
        return rest
    left = Column(page, 40, top, bottom, 247)
    right = Column(page, 308, top, bottom, 247)
    col = left
    rest = list(items)
    while rest:
        kind, text = rest[0]
        ok = col.heading(text) if kind == "h" else col.para(text)
        if ok:
            rest.pop(0)
        elif col is left:
            col = right
        else:
            break
    return rest


def sitrep():
    rnd = random.Random(7)
    title = "Northland Situation Report No. 7"
    pages = [Page() for _ in range(4)]
    for i, p in enumerate(pages):
        chrome(p, i + 1, len(pages), title)

    # Page 1: title across the page, highlights, then two columns.
    p = pages[0]
    p.text(40, 770, "Northland: Situation Report No. 7", size=20, bold=True)
    p.text(40, 748, "Generated for the Relais tests · Covers 1 to 31 October 2026", size=10)
    p.rect(40, 640, 515, 92, fill=0.93)
    p.text(52, 714, "HIGHLIGHTS", size=11, bold=True)
    bullets = [
        "• Floods displaced about 8,000 people in Eastvale and Riverside districts.",
        "• Water trucking and borehole repairs are the first needs named by families.",
        "• The response plan is 38 per cent funded.",
    ]
    for j, b in enumerate(bullets):
        p.text(52, 696 - 16 * j, b, size=10)
    items = [("h", "Situation overview")]
    items += [("p", paragraph(rnd, 4)) for _ in range(2)]
    items += [("h", HEADINGS[0]), ("p", "Partners repaired the borehole of Eastvale camp, which now serves 3,200 people. " + paragraph(rnd, 2))]
    items += [("p", paragraph(rnd, 3)), ("h", HEADINGS[1]), ("p", paragraph(rnd, 4)), ("p", paragraph(rnd, 3))]
    items += [("h", HEADINGS[5]), ("p", paragraph(rnd, 3)), ("p", paragraph(rnd, 4)), ("h", HEADINGS[6])]
    items += [("p", paragraph(rnd, 4)), ("p", paragraph(rnd, 3)), ("p", paragraph(rnd, 4)), ("p", paragraph(rnd, 4))]
    items += [("p", paragraph(rnd, 4)), ("p", paragraph(rnd, 4)), ("p", paragraph(rnd, 4)), ("p", paragraph(rnd, 3))]
    rest = two_columns(p, rnd, 615, 50, items)

    # Page 2: the columns go on, then a table drawn with lines.
    p = pages[1]
    items = rest + [("h", HEADINGS[2]), ("p", paragraph(rnd, 3)), ("p", paragraph(rnd, 2))]
    two_columns(p, rnd, 790, 340, items, interleave=True)
    p.text(40, 315, "Table 1: People reached by district (generated figures)", size=10, bold=True)
    cols = [40, 160, 260, 360, 460, 555]
    rows = [
        ["District", "Displaced", "Water", "Health", "Shelter"],
        ["Eastvale", "4,100", "3,200", "1,150", "620"],
        ["Riverside", "2,300", "1,900", "800", "410"],
        ["Hillside", "900", "450", "390", "120"],
        ["Northland town", "700", "650", "300", "80"],
        ["Total", "8,000", "6,200", "2,640", "1,230"],
    ]
    top = 300
    for r, row in enumerate(rows):
        y = top - 22 * r
        for c, cell in enumerate(row):
            p.text(cols[c] + 6, y - 15, cell, size=9, bold=(r == 0))
        p.line(cols[0], y, cols[-1], y)
    p.line(cols[0], top - 22 * len(rows), cols[-1], top - 22 * len(rows))
    for x in cols:
        p.line(x, top, x, top - 22 * len(rows))

    # Page 3: mostly an image, with a caption.
    p = pages[2]
    p.text(40, 780, "Figure 1: People reached each week (generated chart)", size=10, bold=True)
    p.image("Im1", 40, 360, 515, 400, gray_image(206, 160, 3), 206, 160)
    p.text(40, 335, "Source: generated for the Relais tests.", size=8)

    # Page 4: two columns to the end, with a contacts block.
    p = pages[3]
    items = [("h", HEADINGS[3]), ("p", paragraph(rnd, 4)), ("p", paragraph(rnd, 3)), ("h", HEADINGS[4])]
    items += [("p", paragraph(rnd, 4)), ("h", HEADINGS[7]), ("p", paragraph(rnd, 3)), ("h", "Contacts")]
    items += [("p", "Relais test team, test@relais.invalid")]
    two_columns(p, rnd, 790, 50, items, interleave=True)
    return Pdf().build(pages, title)


# --- The two files of produit/passe-v0.2.md, 7.1 (journey 7) --------------------------------

TEST_LEFT = [
    "Water trucking reached 4,200 people in Site A.",
    "Families who left the river banks in October now live in two collective centres.",
    "Each centre has one latrine block and a single water point.",
    "Partners count 1,150 households in Site A and 640 in Site B.",
]
TEST_RIGHT = [
    "Health partners opened a mobile clinic in Site B.",
    "The clinic sees about 90 patients a day, most of them children.",
    "Vaccination against measles starts next week in both sites.",
    "Rains have flooded the access road twice since the start of the month.",
]


def test_sitrep():
    """3 pages: two columns written row by row, a table of 4 columns, a closing sentence."""
    title = "Test Situation Report No. 1"
    pages = [Page() for _ in range(3)]
    for i, p in enumerate(pages):
        chrome(p, i + 1, len(pages), title)
    p = pages[0]
    p.text(40, 770, "TEST SITUATION REPORT No. 1", size=20, bold=True)
    p.text(40, 748, "Generated for the Relais tests · 1 to 7 November 2026", size=10)
    left = [("h", "Water and sanitation")] + [("p", t + " " + TEST_LEFT[(k + 1) % 4].replace("Water trucking reached 4,200 people in Site A.", "")) for k, t in enumerate(TEST_LEFT)]
    right = [("h", "Health")] + [("p", t + " " + TEST_RIGHT[(k + 1) % 4]) for k, t in enumerate(TEST_RIGHT)]
    kept, p.ops = p.ops, []
    lc = Column(p, 40, 715, 400, 247)
    for kind, text in left:
        lc.heading(text) if kind == "h" else lc.para(text)
    rc = Column(p, 308, 715, 400, 247)
    for kind, text in right:
        rc.heading(text) if kind == "h" else rc.para(text)
    # Row by row across the columns: the order of the file is not the reading order.
    p.ops = kept + sorted(p.ops, key=lambda op: (-float(op.split(b" Tm")[0].split()[-1]), float(op.split(b" Tm")[0].split()[-2])))

    p = pages[1]
    p.text(40, 780, "Table 1: People reached by site (generated figures)", size=11, bold=True)
    cols = [40, 180, 300, 420, 555]
    rows = [
        ["Site", "Households", "People", "Latrines"],
        ["Site A", "1,150", "5,600", "12"],
        ["Site B", "640", "3,100", "7"],
        ["Host families", "380", "1,900", "0"],
        ["Total", "2,170", "10,600", "19"],
    ]
    top = 760
    for r, row in enumerate(rows):
        y = top - 22 * r
        for c, cell in enumerate(row):
            p.text(cols[c] + 6, y - 15, cell, size=10, bold=(r == 0))
        p.line(cols[0], y, cols[-1], y)
    p.line(cols[0], top - 22 * len(rows), cols[-1], top - 22 * len(rows))
    for x in cols:
        p.line(x, top, x, top - 22 * len(rows))

    p = pages[2]
    col = Column(p, 40, 780, 50, 515)
    col.heading("Next steps")
    # A paragraph of several lines: a long press in its middle falls on a word (journey 7, 8.4).
    col.para(
        "Water trucking will continue until 30 November. After that date, partners will hand the "
        "water points over to the committees of both sites, who will run them with the support of "
        "the district water office and a small fund for repairs."
    )
    col.para("Partners will review the needs of the host families at the end of the month.")
    return Pdf().build(pages, title)


def scan():
    pages = []
    for i in range(2):
        p = Page()
        p.image("Im1", 30, 30, 535, 782, gray_image(214, 312, 11 + i, text_like=True), 214, 312)
        pages.append(p)
    return Pdf().build(pages, "Scanned letter without text")


def long_doc():
    rnd = random.Random(25)
    title = "Northland long report"
    total = 25
    pages = [Page() for _ in range(total)]
    rest = []
    for i, p in enumerate(pages):
        chrome(p, i + 1, total, title)
        items = rest + [("h", "Section %d: %s" % (i + 1, HEADINGS[i % len(HEADINGS)]))]
        items += [("p", paragraph(rnd, 4)) for _ in range(6)]
        rest = two_columns(p, rnd, 790, 50, items)[:2]
    return Pdf().build(pages, title)


def french():
    p = Page()
    p.text(40, 780, "Rapport d'essai : Nordland, octobre 2026", size=18, bold=True)
    lines = [
        "Ce document est généré pour les tests de Relais. Les équipes ont distribué",
        "des kits d'hygiène à 2 300 ménages déplacés après les inondations. L'accès",
        "reste difficile pour les camions, et les partenaires demandent un finance-",
        "ment pour maintenir l'approvisionnement en eau jusqu'à la saison sèche.",
    ]
    y = 740
    for line in lines:
        p.text(40, y, line, size=11)
        y -= 15
    p.text(40, y - 12, "Œuvres et coopération", size=13, bold=True)
    p.text(40, y - 34, "Les écoles temporaires accueillent de nouveau les élèves à Nordville.", size=11)
    return Pdf().build([p], "Rapport d'essai")


def main():
    os.makedirs(OUT, exist_ok=True)
    files = {
        "sitrep-two-columns.pdf": sitrep(),
        "scan-no-text.pdf": scan(),
        "long-25-pages.pdf": long_doc(),
        "french-one-column.pdf": french(),
        "test-sitrep.pdf": test_sitrep(),
        "test-scan.pdf": scan(),
    }
    for name, data in files.items():
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(data)
        print("%-24s %7d bytes" % (name, len(data)))


if __name__ == "__main__":
    main()
