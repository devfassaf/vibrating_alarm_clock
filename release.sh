#!/usr/bin/env bash
# release.sh — one command to publish a release the installed apps can actually receive.
#
#   ./release.sh                    build + publish the CURRENT version in build.gradle.kts
#   ./release.sh patch              bump 1.0.0 -> 1.0.1 first (also: minor / major / X.Y.Z)
#   ./release.sh patch "מה חדש..."  release notes as the second argument
#
# Steps: guards -> sync main -> optional bump -> deliverability check -> tests ->
# signed build -> signature verification -> two APK assets -> GitHub release.
set -euo pipefail

REPO="devfassaf/vibrating_alarm_clock"
APK_DIR="apk_versions"          # gitignored; built releases are collected here
GRADLE_FILE="app/build.gradle.kts"
# The version-less asset name. GitHub resolves /releases/latest/download/<name> only for
# an exactly-named asset, and that redirect is what the landing page's download button
# and the in-app updater's fallback both rely on. UpdateContractTest pins this name.
STABLE_APK_NAME="vibealarm.apk"

cd "$(dirname "$0")"

BUMP="${1:-}"
NOTES="${2:-}"

say() { printf '\n\033[1;36m▸ %s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

read_version() {
  sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$GRADLE_FILE" | head -1
}

# versionCode must increase or Android refuses the install. Deriving it from the name
# means the two can never drift apart. Mirrors Versions.versionCode in the app.
version_code_for() {
  local v="$1" major minor patch
  IFS=. read -r major minor patch <<< "$v"
  [ "$minor" -lt 100 ] && [ "$patch" -lt 100 ] || die "minor/patch must stay below 100 for a monotonic versionCode: $v"
  echo $(( major * 10000 + minor * 100 + patch ))
}

bump_version() {
  local current="$1" kind="$2" major minor patch
  IFS=. read -r major minor patch <<< "$current"
  case "$kind" in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "$major.$((minor + 1)).0" ;;
    patch) echo "$major.$minor.$((patch + 1))" ;;
    *)     echo "$kind" ;;   # an explicit X.Y.Z
  esac
}

# ---- guards ------------------------------------------------------------------
command -v gh >/dev/null || die "the GitHub CLI (gh) is not installed"
[ -f "$GRADLE_FILE" ] || die "run this from the repository root"
[ -z "$(git status --porcelain)" ] || die "uncommitted changes — commit or stash first"
grep -q "VIBEALARM_STORE_FILE" "$HOME/.gradle/gradle.properties" 2>/dev/null \
  || die "no release signing config in ~/.gradle/gradle.properties (VIBEALARM_STORE_FILE and friends) — an unsigned or debug-signed build cannot update an installed app"

# ---- sync main ---------------------------------------------------------------
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" = "main" ]; then
  say "Syncing main with GitHub…"
  git fetch origin
  git merge --ff-only origin/main || die "local main has diverged from GitHub — reconcile first"
else
  say "Warning: on branch '$BRANCH', not main — the release is built from this local code"
fi

# ---- version ------------------------------------------------------------------
CURRENT="$(read_version)"
[ -n "$CURRENT" ] || die "could not read versionName from $GRADLE_FILE"

if [ -n "$BUMP" ]; then
  V="$(bump_version "$CURRENT" "$BUMP")"
  CODE="$(version_code_for "$V")"
  say "Bumping $CURRENT -> $V (versionCode $CODE)…"
  # Rewrite both, together: a versionName without a matching versionCode installs as
  # "the same build" and the update silently does nothing.
  sed -i.bak \
    -e "s/^\([[:space:]]*\)versionCode = .*/\1versionCode = $CODE/" \
    -e "s/^\([[:space:]]*\)versionName = \".*\"/\1versionName = \"$V\"/" \
    "$GRADLE_FILE"
  rm -f "$GRADLE_FILE.bak"
  git add "$GRADLE_FILE"
  git commit -q -m "release v$V"
  say "Bump committed — remember to push it"
else
  V="$CURRENT"
fi
TAG="v$V"

# A version with four components is not "smaller" to an installed app — it is
# INVISIBLE. The app parses exactly three components, so 1.0.0.1 compares EQUAL to an
# installed 1.0.0 and every device answers "up to date". Checked before the build so a
# typo costs a second instead of a whole release.
case "$V" in
  *.*.*.*) die "version '$V' can never reach a device: the app compares three components, so it is indistinguishable from ${V%.*}. Use a real patch bump." ;;
esac
printf '%s' "$V" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$' \
  || die "version '$V' is not X.Y.Z — the app would not recognise it as newer"

# The tag must not already exist, or gh release create fails after a full build.
if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  die "release $TAG already exists on $REPO — bump the version first"
fi

APK="$APK_DIR/vibealarm-$TAG.apk"
APK_LATEST="$APK_DIR/$STABLE_APK_NAME"

# ---- tests --------------------------------------------------------------------
say "Running tests…"
./gradlew --quiet testDebugUnitTest >/dev/null 2>&1 \
  || die "tests failed — never release a broken alarm clock (run ./gradlew testDebugUnitTest for details)"

# ---- signed build + verification -----------------------------------------------
say "Building signed APK ($TAG)…"
./gradlew --quiet assembleRelease >/dev/null 2>&1 \
  || die "build failed — run ./gradlew assembleRelease for details"

BUILT="app/build/outputs/apk/release/app-release.apk"
[ -f "$BUILT" ] || die "expected $BUILT to exist after the build"

APKSIGNER="$(ls -d "$HOME"/Library/Android/sdk/build-tools/*/apksigner 2>/dev/null | sort | tail -1 || true)"
if [ -n "$APKSIGNER" ]; then
  say "Verifying the signature…"
  # A debug-signed APK installs fine on a clean phone and then can NEVER update an
  # existing install: Android refuses a signature change. Catch it here, not in the field.
  "$APKSIGNER" verify --print-certs "$BUILT" 2>/dev/null | grep -q "CN=" \
    || die "signature verification failed — do NOT publish"
else
  say "Warning: apksigner not found; skipping signature verification"
fi

mkdir -p "$APK_DIR"
cp "$BUILT" "$APK"
cp "$BUILT" "$APK_LATEST"
say "Built: $APK ($(du -h "$APK" | cut -f1 | tr -d ' '))"

# ---- publish --------------------------------------------------------------------
# This body is what a user reads in the app's update prompt. Hebrew, user-facing, one
# bullet per change — the app extracts exactly the section under "## מה חדש".
[ -n "$NOTES" ] || NOTES="שיפורים ותיקונים כלליים"
BODY="## מה חדש

$NOTES"

say "Publishing the GitHub release…"
if OUT="$(gh release create "$TAG" "$APK" "$APK_LATEST" --repo "$REPO" -t "$TAG" -n "$BODY" 2>&1)"; then
  printf '\n\033[1;32m✓ Published %s. Installed apps will offer it the next time they are opened.\033[0m\n' "$TAG"
  printf '   Download page: https://github.com/%s/releases/latest/download/%s\n' "$REPO" "$STABLE_APK_NAME"
else
  printf '\n\033[1;33mgh: %s\033[0m\n' "$OUT"
  cat <<EOT

⚠  Could not publish with the current gh account. Publish manually (2 minutes):
   1. https://github.com/$REPO/releases/new
   2. Tag: $TAG   <- type it with an ENGLISH keyboard; an invisible bidi mark from a
      Hebrew-context copy-paste breaks version detection on every device
   3. Attach BOTH files:
        $(pwd)/$APK
        $(pwd)/$APK_LATEST   <- the download button needs this exact name or it 404s
   4. Publish release
EOT
  # An unpublished release is a failed release: the build exists on this machine only,
  # and every device keeps answering "up to date". The exit code has to say so.
  die "NOT PUBLISHED: $TAG exists only locally — no device can see it until the steps above are done"
fi
