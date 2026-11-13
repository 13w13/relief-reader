# Re.L, an unofficial ReliefWeb reader for Android

**Not affiliated with ReliefWeb or OCHA.**

**Wake up and read the humanitarian news from [ReliefWeb](https://reliefweb.int).**

ReliefWeb closed its mobile apps in 2018, so I built a reader app.

Re.L is a preview, built in the open in my free time, to stay closer to the humanitarian news. A lot is still missing, and feedback is welcome: [open an issue](https://github.com/13w13/relief-reader/issues). This is a personal project, not connected to my employer.

<img src="docs/screenshots/today.png" width="260" alt="Today, the first page of Re.L"> <img src="docs/screenshots/connect.png" width="260" alt="The Connect to ReliefWeb screen">

*Screenshots use made-up sample data.*

## What it does

- **Reads like a news app.** A Today page that ends, then the countries, crises and organizations you follow.
- **PDFs as text.** Most reports on ReliefWeb are PDFs. Once one is on your phone, Re.L shows it as readable text, with the original one tap away.
- **Works offline.** Reports from what you follow are stored on your phone.
- **Keeps what you read.** Save, highlight, copy with a citation, and export your highlights.
- **No AI in the app.** No generated summaries, no scores. You read what the organizations wrote.
- **No ads, no account, no tracking.**

Every report shows its source organization, its dates and a link to ReliefWeb.

## Get started

1. **Install** the APK from the [latest release](https://github.com/13w13/relief-reader/releases/latest).
2. **Open Re.L** and tap **Continue with headlines only**. You can read ReliefWeb's latest headlines straight away.
3. **Ask ReliefWeb for a free appname** with your work email. ReliefWeb replies within two business days. Enter the appname in the app to unlock full reports, country pages and search.

The next two sections give the details.

## Install

Re.L is not on Google Play. It needs Android 8.0 or later.

1. On your phone, open the [latest release](https://github.com/13w13/relief-reader/releases/latest) and download the `.apk` file under **Assets**. Your browser may warn that the file could be harmful; download it anyway only from this page.
2. Open the file. The first time, Android asks you to allow installs from your browser or file manager. Google Play Protect may also warn about an app it doesn't know.
3. To update, download the new APK and install it over the old one. Your countries, Library and settings stay.

Every release is signed with the same key. To check an APK before you install it, for example with AppVerifier or Obtainium, compare the SHA-256 fingerprint of its signing certificate with this one:

```
37:7E:8D:D3:41:4E:89:4C:7F:65:D0:C0:F7:99:D3:D2:17:62:01:46:98:40:88:02:9F:6C:4C:30:F7:11:7E:26
```

To hear about new versions, use **Watch > Custom > Releases** on this page (needs a GitHub account).

**In Brazil, Indonesia, Singapore and Thailand,** Android has blocked apps from unverified developers since 30 September 2026, on certified devices. Re.L is not registered with Google's developer verification, so installing it there goes through Android's advanced flow: a one-time setup, a restart and a 24-hour wait. Google plans to extend the rule to other countries from 2027. Source: [Android developer verification](https://developer.android.com/developer-verification).

## Your ReliefWeb appname

ReliefWeb asks every app that reads its API to use an approved name, called an appname. Re.L has none built in: each reader uses their own.

1. Fill in ReliefWeb's [appname request form](https://docs.google.com/forms/d/e/1FAIpQLScR5EE_SBhweLLg_2xMCnXNbT6md4zxqIB00OL0yZWyrqX_Nw/viewform). The form is linked from the [API documentation](https://apidoc.reliefweb.int/#appname), in case its address changes.
2. Use your work email. ReliefWeb does not accept Gmail, Yahoo or Hotmail addresses.
3. ReliefWeb replies by email, within two business days according to the form.
4. Enter the appname in Re.L when you first open it, or later in Settings > ReliefWeb access. It works at once and stays on your phone.

The form asks two things that may not be obvious:
- **Purpose:** one sentence is enough, for example "Reading ReliefWeb on my phone with Re.L, an open-source reader: https://github.com/13w13/relief-reader".
- **Preferred appname:** your organization, a purpose and a few random letters or numbers, in the form `<organization>-reader-<random>`. ReliefWeb may adjust it.

Keep your appname to yourself. Don't publish it, and don't build it into a fork. ReliefWeb ties it to your name and email, and may log the calls made with it.

## Privacy

Re.L collects no data. It has no account, no ads, no analytics and no crash reporting.

- **Only ReliefWeb.** The app talks to `api.reliefweb.int` for reports and to `reliefweb.int` for PDFs, images, the headlines feed and the "Most read" file.
- **Everything stays on the phone:** what you follow, what you read, your highlights and your appname.
- **Permissions:**
  - internet access and network state, to download and to wait for Wi-Fi;
  - notifications, for the daily summary and for alerts. Alerts are off until you turn them on, and you can turn either off in Settings.

## Whose content this is

Reports, PDFs and images come from ReliefWeb and its API, run by the UN Office for the Coordination of Humanitarian Affairs (OCHA). They belong to ReliefWeb and to the organizations that publish them, and [ReliefWeb's terms](https://reliefweb.int/terms-conditions) apply. Re.L uses no ReliefWeb or OCHA logo.

## Feedback and problems

Open an [issue](https://github.com/13w13/relief-reader/issues) for a bug or a suggestion. For a bug, please include:
- what you did and what you expected;
- your phone model and Android version;
- the app version, shown in Settings under About Re.L.

Never paste your appname in an issue.

For a security problem, don't open a public issue. [Report it privately](https://github.com/13w13/relief-reader/security/advisories/new) instead. See [SECURITY.md](SECURITY.md).

## Build it yourself

Re.L is written in Kotlin with Jetpack Compose. You need JDK 17 and the Android SDK.

```bash
./gradlew :app:assembleDebug
```

The build needs no secret and no appname, and the tests run on made-up sample data. This is a spare-time project: pull requests are welcome, and answers may take a while.

## Licence

Re.L is free software under the GNU General Public License, version 3 or later (`GPL-3.0-or-later`). See [LICENSE](LICENSE), and [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for the libraries it includes. The licence covers the code only, not the reports.

---

<sub>Built with the help of AI coding tools. The app itself contains none.</sub>
