# F-Droid submission status

## Current status: waiting (policy: 3 months of active development)

The first inclusion request, `fdroid/fdroiddata!57539`, was closed by an F-Droid
maintainer (`linsui`) on **2026-11-16** with this reason:

> Closing because:
>   - Merge Request template is not followed.
>   - This app doesn't meet the policy that it needs to be maintained for more
>     than 3 months.

This is not a build or metadata problem. The [Inclusion Policy](https://f-droid.org/docs/Inclusion_Policy/)
says:

> The application should have been actively developed for at least 3 months,
> unless: the application has been proven to have some users; the author has
> been maintaining other apps on F-Droid; the application is developed only for
> an event.

MeshLink's repository history starts on **2026-11-13**, so it does not meet the
3-month requirement yet. The earliest reasonable resubmission date is around
**2027-02-16**, provided the project stays active until then.

Nothing in the app or metadata is blocked. `fdroid lint` passes with 0 issues.

## What is already done

- License: GPL-3.0-or-later, `LICENSE` in the repository root.
- Distinct application ID: `com.meshlink.app`.
- Public source: <https://github.com/Iamhasaanzahid/meshlink-android>.
- Tagged releases: `v1.6.0` (versionCode 8) with an installable APK attached.
- Fastlane metadata in the upstream repo:
  `fastlane/metadata/android/en-US/` with `title.txt`,
  `short_description.txt`, `full_description.txt`, `changelogs/3.txt`,
  `images/icon.png` and `images/featureGraphic.png`.
- `metadata/com.meshlink.app.yml` uses the **full commit hash** (not a tag),
  `gradle: yes`, no `subdir`, and `output: app/build/outputs/apk/release/*.apk`.
- Release signing is opt-in (`KEYSTORE_FILE` and friends), so F-Droid gets an
  unsigned release APK.

## Resubmission checklist

Before opening the next merge request:

1. Keep committing to the project (the 3-month clock must be visibly satisfied).
2. Add phone screenshots to `fastlane/metadata/android/en-US/images/phoneScreenshots/`.
3. Open the request from the **MR template** and keep every checklist item,
   including the introduction, why the app exists, and the unique value.
4. Metadata file: only `metadata/com.meshlink.app.yml`, LF line endings, only
   the latest version, `commit:` set to the full hash of the release commit.
5. Reference any related RFP issue (or open one at
   <https://gitlab.com/fdroid/rfp/-/issues>).
6. Mention in the MR that the fork pipeline needs to be triggered by a
   maintainer if GitLab asks for identity verification.

## Alternative distribution while waiting

F-Droid's policy gate does not prevent direct distribution:

- GitHub Releases: <https://github.com/Iamhasaanzahid/meshlink-android/releases>
- A self-hosted F-Droid repository or an IzzyOnDroid inclusion request are both
  possible; neither is set up yet.
