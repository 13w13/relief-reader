#!/usr/bin/env python3
"""Synthetic fixtures for the tests of Re.L, in the shape of the ReliefWeb API and RSS feeds.

Invented reports, organizations, people-free text and relais.test addresses: no content from
ReliefWeb. Only reference data stays real, because it is factual and the app needs it: the
countries (fixtures/countries.json, unchanged), formats, languages.

    python3 tools/make_fixtures.py   # from the repository root; deterministic

Writes fixtures/{headlines,sudan-reports,sync-sample,report-full,disasters-current}.json and
api/src/test/resources/{rw_headlines,rw_sdn}.xml. The tests that read them are FixtureTests,
ApiTests (RSS), ScreenshotTest and CrisesTest.
"""
import datetime as dt
import json
import random
from email.utils import format_datetime
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
rng = random.Random(20261113)
BASE = dt.datetime(2026, 11, 12, 16, 0, tzinfo=dt.timezone.utc)

COUNTRIES = {c["fields"]["id"]: c["fields"] for c in json.loads((ROOT / "fixtures/countries.json").read_text())["data"]}
SUDAN = 220

FORMATS = {10: "Situation Report", 3: "Analysis", 5: "Assessment", 8: "News and Press Release", 4: "Appeal", 12: "Map", 12570: "Infographic"}
THEMES = [(990201, "Food and Nutrition"), (990202, "Health"), (990203, "Water Sanitation Hygiene"),
          (990204, "Protection and Human Rights"), (990205, "Shelter and Non-Food Items"), (990206, "Coordination")]
LANGUAGES = [(267, "English", "en"), (268, "French", "fr")]
SOURCES = [
    {"id": 990001, "name": "Test Coordination Office", "shortname": "TCO"},
    {"id": 990002, "name": "Test Food Programme", "shortname": "TFP"},
    {"id": 990003, "name": "Test Refugee Agency", "shortname": "TRA"},
    {"id": 990004, "name": "Test Health Network", "shortname": "THN"},
    {"id": 990005, "name": "Test Research Institute", "shortname": "TRI"},
]
for s in SOURCES:
    s["homepage"] = f"https://relais.test/{s['shortname'].lower()}"
    s["type"] = {"id": 990300, "name": "Test organization"}

DISASTERS = [
    {"id": 990101, "name": "Sudan: Test complex emergency - 2026", "glide": "CE-2026-000101-SDN", "country": SUDAN, "type": (41764, "Complex Emergency")},
    {"id": 990102, "name": "Chad: Test floods - Sep 2026", "glide": "FL-2026-000102-TCD", "country": None, "type": (4611, "Flood")},
    {"id": 990103, "name": "Test region: Test epidemic - Oct 2026", "glide": "EP-2026-000103-TST", "country": None, "type": (4642, "Epidemic")},
]

SENTENCES = [
    "Partners reached {n:,} people with water trucking in the {where} this week.",
    "Mobile clinics visited {k} villages that the floods had cut off.",
    "Food prices in the main market rose by {p} per cent since last month.",
    "Teams referred {k} people to specialised protection services.",
    "Road access to the river crossing remains limited for heavy trucks.",
    "The response plan is {p} per cent funded, according to the test tracking service.",
    "Families named shelter, water and food as their first needs.",
    "Temporary learning spaces opened in {k} sites for children who fled.",
    "Partners will hand the water points over to the site committees at the end of the month.",
]
WHERE = ["northern districts", "eastern sites", "river towns", "border areas", "camps of the south"]


def iso(t):
    return t.strftime("%Y-%m-%dT%H:%M:%S+00:00")


def sentence():
    return rng.choice(SENTENCES).format(n=rng.randint(800, 9000), k=rng.randint(3, 40), p=rng.randint(12, 64), where=rng.choice(WHERE))


def body(paragraphs=3, rich=False):
    parts = []
    if rich:
        parts.append("<h2>Highlights</h2><ul>" + "".join(f"<li>{sentence()}</li>" for _ in range(3)) + "</ul>")
    for i in range(paragraphs):
        if rich and i % 2 == 1:
            parts.append(f"<h3>{rng.choice(['Water', 'Health', 'Protection', 'Food security', 'Access'])}</h3>")
        parts.append("<p>" + " ".join(sentence() for _ in range(rng.randint(2, 4))) + "</p>")
    return "".join(parts)


def country_ref(cid, primary=False):
    c = COUNTRIES[cid]
    ref = {"id": cid, "name": c["name"], "shortname": c.get("shortname") or c["name"], "iso3": c.get("iso3", "")}
    if primary:
        ref["primary"] = True
    return ref


def a_file(rid, pdf=True):
    f = {
        "id": str(rid * 10), "url": f"https://relais.test/files/{rid}.pdf", "filename": f"{rid}.pdf",
        "mimetype": "application/pdf", "filesize": str(rng.randint(80_000, 4_000_000)), "pagecount": rng.randint(2, 30),
        "description": "Invented PDF",
    }
    if pdf:
        f["preview"] = {"url": f"https://relais.test/previews/{rid}.png", "url-large": f"https://relais.test/previews/{rid}-large.png",
                        "url-small": f"https://relais.test/previews/{rid}-small.png", "url-thumb": f"https://relais.test/previews/{rid}-thumb.png"}
    return f


def slug(text):
    return "".join(ch if ch.isalnum() else "-" for ch in text.lower()).strip("-").replace("--", "-")


def report(rid, created, country, fmt, *, headline=False, text=True, files=False, image=False, disaster=None, rich=False):
    source = rng.choice(SOURCES)
    cname = COUNTRIES[country].get("shortname") or COUNTRIES[country]["name"]
    title = {
        10: f"{cname}: Test situation report No. {rng.randint(2, 60)}",
        3: f"{cname}: Test analysis of food insecurity",
        5: f"{cname}: Test rapid needs assessment, {rng.choice(WHERE)}",
        8: f"Test partners scale up the response in {cname}",
        4: f"{cname}: Test flash appeal 2027",
        12: f"{cname}: Test map of displacement sites",
        12570: f"{cname}: Test humanitarian snapshot",
    }[fmt]
    language = LANGUAGES[0] if rng.random() < 0.85 else LANGUAGES[1]
    fields = {
        "id": rid, "title": title, "status": "published",
        "primary_country": country_ref(country),
        "country": [country_ref(country, primary=True)],
        "source": [source],
        "language": [{"id": language[0], "name": language[1], "code": language[2]}],
        "theme": [{"id": t, "name": n} for t, n in rng.sample(THEMES, 2)],
        "format": [{"id": fmt, "name": FORMATS[fmt]}],
        "url_alias": f"https://reliefweb.int/report/{slug(cname)}/{slug(title)}-{rid}",
        "origin": f"https://relais.test/origin/{rid}",
        "date": {"created": iso(created), "original": iso(created - dt.timedelta(hours=6)), "changed": iso(created + dt.timedelta(minutes=20))},
    }
    if text:
        fields["body-html"] = body(rng.randint(2, 4), rich)
    if files:
        fields["file"] = [a_file(rid)]
    if disaster:
        fields["disaster"] = [{"id": disaster["id"], "name": disaster["name"], "glide": disaster["glide"], "status": "ongoing"}]
    if headline:
        fields["headline"] = {"title": title, "summary": "Invented summary: " + sentence()}
        if image:
            fields["headline"]["image"] = {
                "url": f"https://relais.test/images/{rid}.jpg", "url-large": f"https://relais.test/images/{rid}-large.jpg",
                "url-small": f"https://relais.test/images/{rid}-small.jpg", "url-thumb": f"https://relais.test/images/{rid}-thumb.jpg",
                "width": "1200", "height": "800", "caption": "Invented caption of a test image.", "copyright": "© Re.L test images",
            }
    return {"id": rid, "fields": fields}


def envelope(items, facets=None):
    out = {"time": 12, "took": 4, "totalCount": len(items) * 7, "count": len(items), "data": items}
    if facets:
        out["embedded"] = {"facets": facets}
    return out


def write_json(path, value):
    (ROOT / path).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    others = [cid for cid, c in COUNTRIES.items() if c.get("status") in ("ongoing", "current") and cid not in (SUDAN, 254)][:12]
    t = BASE

    # Headlines: 50, each with its editors' title and text; some with files, a few with an image.
    headlines = []
    for i in range(50):
        t -= dt.timedelta(hours=rng.randint(2, 9))
        country = SUDAN if i % 5 == 0 else rng.choice(others)
        headlines.append(report(990_000_100 + i, t, country, rng.choice([10, 3, 5, 8, 4]), headline=True,
                                files=i % 3 == 0, image=i % 12 == 0, disaster=DISASTERS[0] if country == SUDAN else None, rich=i % 4 == 0))
    write_json("fixtures/headlines.json", envelope(headlines))

    # Sudan's page: 50 documents, maps and infographics without text, and the format facets.
    t = BASE
    sudan = []
    for i in range(50):
        t -= dt.timedelta(hours=rng.randint(1, 7))
        fmt = [10, 10, 3, 5, 8, 12, 12570, 4][i % 8]
        visual = fmt in (12, 12570)
        sudan.append(report(990_000_200 + i, t, SUDAN, fmt, text=not visual, files=visual or i % 2 == 0,
                            disaster=DISASTERS[0] if i % 6 == 0 else None, headline=i == 3, image=i == 3))
    counts = {}
    for item in sudan:
        f = item["fields"]["format"][0]
        counts[f["id"]] = counts.get(f["id"], 0) + 1
    facets = {
        "format.id": {"type": "term", "data": [{"value": str(k), "count": v * 7} for k, v in sorted(counts.items(), key=lambda kv: -kv[1])]},
        "format_names": {"type": "term", "data": [{"value": FORMATS[k], "count": v * 7} for k, v in sorted(counts.items(), key=lambda kv: -kv[1])]},
    }
    write_json("fixtures/sudan-reports.json", envelope(sudan, facets))

    # The sync: headlines and the reports of followed countries, without text.
    sync = []
    for i, item in enumerate(headlines[:7] + sudan[:26]):
        fields = dict(item["fields"])
        fields.pop("body-html", None)
        fields.pop("disaster", None)
        sync.append({"id": item["id"], "fields": fields})
    write_json("fixtures/sync-sample.json", envelope(sync))

    # One report with a long text, for the reader's screenshot.
    full = report(990_000_300, BASE - dt.timedelta(days=2), SUDAN, 10, headline=True, files=True, rich=True)
    full["fields"]["body-html"] = body(10, rich=True)
    full["fields"]["url"] = "https://reliefweb.int/node/990000300"
    write_json("fixtures/report-full.json", envelope([full]))

    # Disasters that are not over.
    disasters = []
    for i in range(50):
        base = DISASTERS[i % len(DISASTERS)]
        country = base["country"] or others[i % len(others)]
        created = BASE - dt.timedelta(days=3 * i + 2)
        cname = COUNTRIES[country].get("shortname") or COUNTRIES[country]["name"]
        name = base["name"] if i < len(DISASTERS) else f"{cname}: Test {base['type'][1].lower()} - {created:%b %Y}"
        # The first three are the crises the reports name; the others are new ids.
        did = base["id"] if i < len(DISASTERS) else 990_200 + i
        disasters.append({"id": did, "fields": {
            "id": did, "name": name, "status": "ongoing" if i % 4 else "alert",
            "glide": f"{base['glide'][:2]}-2026-{did:06d}-{COUNTRIES[country].get('iso3', 'tst').upper()}",
            "primary_country": country_ref(country), "country": [country_ref(country, primary=True)],
            "primary_type": {"id": base["type"][0], "name": base["type"][1]}, "type": [{"id": base["type"][0], "name": base["type"][1]}],
            "url_alias": f"https://reliefweb.int/disaster/test-{did}",
            "date": {"created": iso(created), "event": iso(created - dt.timedelta(days=1)), "changed": iso(created + dt.timedelta(days=1))},
            "description-html": "<p>Invented description of an invented crisis, for the tests of Re.L.</p>",
        }})
    write_json("fixtures/disasters-current.json", envelope(disasters))

    # RSS: the headlines feed (its first item is checked field by field in ApiTests) and a country feed.
    write_rss("api/src/test/resources/rw_headlines.xml", "ReliefWeb - Headlines (test)", headlines[:20], first_checked=True)
    write_rss("api/src/test/resources/rw_sdn.xml", "ReliefWeb - Sudan (test)", sudan[:20])


def write_rss(path, title, items, first_checked=False):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           '<rss xmlns:atom="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/" version="2.0">',
           f'<channel><title>{escape(title)}</title><description>Synthetic feed for the tests of Re.L</description>',
           '<link>https://reliefweb.int/updates</link><language>en</language>',
           f'<pubDate>{format_datetime(BASE)}</pubDate><lastBuildDate>{format_datetime(BASE)}</lastBuildDate>']
    for i, item in enumerate(items):
        f = item["fields"]
        created = dt.datetime.fromisoformat(f["date"]["created"])
        country = f["primary_country"]["shortname"]
        source = f["source"][0]["name"]
        fmt = f["format"][0]["name"]
        item_title = f["title"]
        summary = "Invented summary: " + sentence()
        length = rng.randint(80_000, 4_000_000)
        themes = [t["name"] for t in f["theme"]]
        if first_checked and i == 0:
            # The values ApiTests checks.
            country, source, fmt = "Chad", "Test Food Programme", "Analysis"
            item_title = "Test: food insecurity rises in the eastern sites"
            summary = "Test estimates 1.05 million people face crisis-level hunger in the invented districts."
            length = 1_234_567
            themes = ["Food and Nutrition", "Health"]
        description = (f'<div class="tag country">Country: {escape(country)}</div>'
                       f'<div class="tag source">Source: {escape(source)}</div>'
                       f'<p><img src="https://relais.test/previews/{item["id"]}-thumb.png" alt></p><p>{escape(summary)}</p>')
        out.append("<item>")
        out.append(f"<title>{escape(item_title)}</title><link>{f['url_alias']}</link>")
        out.append(f'<guid isPermaLink="true">{f["url_alias"]}</guid><pubDate>{format_datetime(created)}</pubDate>')
        out.append(f"<description>{escape(description)}</description>")
        for c in [country, fmt, *themes]:
            out.append(f"<category>{escape(c)}</category>")
        out.append(f"<author>{escape(source)}</author>")
        if i % 3 == 0:
            out.append(f'<media:content url="https://relais.test/images/{item["id"]}.jpg" type="image/jpeg" medium="image">'
                       f"<media:title>Invented caption.</media:title><media:copyright>© Re.L test images</media:copyright></media:content>")
        out.append(f'<enclosure url="https://relais.test/files/{item["id"]}.pdf" length="{length}" type="application/pdf"/>')
        out.append("</item>")
    out.append("</channel></rss>")
    (ROOT / path).write_text("\n".join(out) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
