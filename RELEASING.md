# Publishing a version

For the maintainer. Each version is one commit on `main`, then a tag `vX.Y.Z`; `release.yml` builds, signs and publishes the APK after approval in the `release` environment.

## In the version commit

- `app/build.gradle.kts`: `versionCode` (+1), `versionName`, and **`releaseDate`**, the day you plan to publish (`yyyy-MM-dd`).
  - Two months after that day, Today suggests checking for a newer version. It uses the phone's date only and sends nothing.
  - Write it by hand, never the build time: the build must stay reproducible (F-Droid).
  - `ci.yml` checks that it is a real date. `release.yml` refuses a tag more than 7 days away from it: if publishing slips, change the date in a new commit before tagging.
- `release-notes/vX.Y.Z.md`: the GitHub Release text.
- `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`: 500 characters at most.
- What's new (`WhatsNew.kt` and its strings): 3 to 5 lines, only what is in this version.

## Asking older versions to update

In `release-notes/vX.Y.Z.md`, one line, exactly:

```
<!-- please-update-below: 0.7.0 -->
```

Every installed version **below** 0.7.0 then shows "Please update Re.L to X.Y.Z." on Today, with Download and See the release, and without "Not now". It stays until the reader updates. Nothing is blocked.

- The app reads this line from the release it already asks GitHub about: no extra request.
- Only the latest release counts. Write the line again in each later release that should keep asking; a release without it asks nothing.
- The version must be written without "v", and be lower than or equal to the release's own version. Otherwise the app ignores the line, as it does with two such lines.
- It is an HTML comment: GitHub does not show it in the Release text.
- Readers with "Check for new versions" turned off, and builds without the check (F-Droid), never see it.
