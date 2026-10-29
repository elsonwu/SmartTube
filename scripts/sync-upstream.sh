#!/usr/bin/env bash
#
# Merge upstream SmartTube into the parental-limit fork.
#
#   ./scripts/sync-upstream.sh            # check + merge
#   ./scripts/sync-upstream.sh --check    # only report, don't merge
#   ./scripts/sync-upstream.sh --abort    # undo a merge in progress
#
# See FORK_MAINTENANCE.md for the full rationale and the per-file
# conflict-resolution guide.

set -euo pipefail

UPSTREAM_REMOTE="upstream"
UPSTREAM_URL="https://github.com/yuliskov/SmartTube.git"
UPSTREAM_BRANCH="master"
FORK_BRANCH="feature/parental-time-limit"

cd "$(dirname "$0")/.."

MODE="merge"
case "${1:-}" in
    --check) MODE="check" ;;
    --abort) MODE="abort" ;;
    "") ;;
    *) echo "usage: $0 [--check|--abort]" >&2; exit 2 ;;
esac

if [ "$MODE" = "abort" ]; then
    git merge --abort || true
    echo "merge aborted"
    exit 0
fi

# ── 1. upstream remote ────────────────────────────────────────────────────
if git remote | grep -qx "$UPSTREAM_REMOTE"; then
    echo "[*] upstream remote already present"
else
    echo "[*] adding upstream remote"
    git remote add "$UPSTREAM_REMOTE" "$UPSTREAM_URL"
fi

echo "[*] fetching upstream"
git fetch "$UPSTREAM_REMOTE" "$UPSTREAM_BRANCH"

# ── 2. how far behind are we ──────────────────────────────────────────────
BASE=$(git merge-base HEAD "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH")
BEHIND=$(git rev-list --count "$BASE..$UPSTREAM_REMOTE/$UPSTREAM_BRANCH")
AHEAD=$(git rev-list --count "$BASE..HEAD")

echo
echo "══════════════════════════════════════════════════════════════"
echo "  fork branch : $FORK_BRANCH"
echo "  upstream    : $UPSTREAM_REMOTE/$UPSTREAM_BRANCH"
echo "  behind      : $BEHIND commit(s)"
echo "  ahead       : $AHEAD commit(s) (our parental-limit work)"
echo "══════════════════════════════════════════════════════════════"
echo

git log --oneline --no-decorate "$BASE..$UPSTREAM_REMOTE/$UPSTREAM_BRANCH" | head -20
echo

if [ "$BEHIND" -eq 0 ]; then
    echo "[+] Already up to date with upstream."
    exit 0
fi

if [ "$MODE" = "check" ]; then
    echo "[*] --check given, not merging."
    exit 0
fi

# ── 3. merge ──────────────────────────────────────────────────────────────
if [ "$(git rev-parse --abbrev-ref HEAD)" != "$FORK_BRANCH" ]; then
    echo "[*] switching to $FORK_BRANCH"
    git checkout "$FORK_BRANCH"
fi

# Only tracked modifications block a merge. Untracked files (build outputs,
# local keystores, personal scripts) are fine.
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "[!] tracked files are modified - commit or stash them first:" >&2
    git status --porcelain --untracked-files=no >&2
    exit 1
fi

echo "[*] merging upstream/$UPSTREAM_BRANCH ..."
set +e
git merge --no-edit "$UPSTREAM_REMOTE/$UPSTREAM_BRANCH"
MERGE_RC=$?
set -e

if [ "$MERGE_RC" -ne 0 ]; then
    echo
    echo "══════════════════════════════════════════════════════════════"
    echo "  MERGE CONFLICT"
    echo "══════════════════════════════════════════════════════════════"
    echo
    git diff --name-only --diff-filter=U | while read -r f; do
        echo "  CONFLICT: $f"
    done
    echo
    cat <<'GUIDE'
  Resolve each conflict with the guide in FORK_MAINTENANCE.md §5
  ("Per-file resolution guide"). Quick rules:

    smarttubetv/build.gradle ................ keep OUR version of the
        applicationId / abiFilters / APK-name blocks, take THEIRS
        everywhere else.
    smarttubetv/src/main/AndroidManifest.xml .. keep OUR <activity>
        block for ParentalLimitActivity, take THEIRS elsewhere.
    .../ui/main/MainApplication.java .......... keep OUR 2 imports +
        the 2-line init block in onCreate().
    .../misc/AppDataSourceManager.java ........ keep OUR import +
        the SettingsItem block.
    .../settings/GeneralSettingsPresenter.java . keep OUR menu entry.
    common/src/*/res/values*/strings.xml ..... keep OUR parental_limit_*
        strings, take THEIRS for everything else.
    common/src/ststable/res/values/update_urls.xml ... keep OURS (empty).
    smarttubetv/src/ststable/res/values/strings.xml ..... keep OURS
        (SmallTube).

  Then:

    git add <resolved files>
    git commit          # merge commit
    git submodule update --init --recursive   # upstream may have bumped them
    ./scripts/verify-fork.sh               # rebuild + sanity checks

  To bail out:  git merge --abort
GUIDE
    exit 1
fi

echo
echo "[+] Merge succeeded."
echo
echo "[*] syncing the git submodules (upstream often bumps MediaServiceCore"
echo "    and SharedModules - a stale checkout breaks the build with"
echo "    'cannot find symbol' errors in upstream's own files)"
git submodule update --init --recursive
echo
cat <<'NEXT'
  Next steps:

    1. Rebuild and sanity check:
         ./scripts/verify-fork.sh

    2. Check the parental-limit code still compiles against the new
       upstream APIs (ParentalLimitController touches PlaybackPresenter,
       PlaybackView and AppDialogPresenter).

    3. Re-verify the integration points listed in FORK_MAINTENANCE.md §5 -
       upstream refactors often move AppDialogPresenter / PlaybackPresenter.

    4. Test on the TV (FORK_MAINTENANCE.md §7, acceptance tests).

    5. Tag the result:
         git tag fork/v<upstream-version>-parental<N>
         git push origin --tags
NEXT
