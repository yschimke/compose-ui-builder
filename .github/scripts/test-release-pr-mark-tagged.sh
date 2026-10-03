#!/usr/bin/env bash
# Self-test for release-pr-mark-tagged.sh, run by CI on every PR.
#
# The script only matters on the push after a release cut outside release-please, so a mistake in
# it would surface as a release pipeline wedged again, or as a label moved on the wrong PR. Each
# case runs it against a stub `gh` that answers from fixed state and records every write.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UNDER_TEST="${SCRIPT_DIR}/release-pr-mark-tagged.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "${tmp}"' EXIT

failures=0
fail() {
  echo "FAIL: $1" >&2
  failures=$((failures + 1))
}

# The stub: PENDING_TSV is what the issues listing returns (after --jq), TAGS and RELEASES are the
# tags and releases that exist, and every write is appended to WRITES.
mkdir -p "${tmp}/bin"
cat > "${tmp}/bin/gh" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  "api repos/o/r/issues?"*)
    [[ "${LIST_FAILS:-}" == 1 ]] && exit 1
    printf '%b' "${PENDING_TSV}" ;;
  "api repos/o/r/git/ref/tags/"*)
    tag="${2##*/}"; [[ " ${TAGS} " == *" ${tag} "* ]] ;;
  "release view "*)
    [[ " ${RELEASES} " == *" $3 "* ]] ;;
  "api --method "*)
    echo "$*" >> "${WRITES}" ;;
  *) echo "unexpected gh $*" >&2; exit 2 ;;
esac
EOF
chmod +x "${tmp}/bin/gh"

run() {
  : > "${tmp}/writes"
  PATH="${tmp}/bin:${PATH}" GITHUB_REPOSITORY=o/r WRITES="${tmp}/writes" "${UNDER_TEST}" \
    > "${tmp}/out" 2>&1
  status=$?
  [[ ${status} -eq 0 ]] || fail "$1: exited ${status}"
}

writes() { cat "${tmp}/writes"; }

# A release cut by hand: tag and release exist, so the PR is relabelled.
PENDING_TSV='420\tgithub-actions[bot]\tchore(main): release 3.79.0\n' TAGS="v3.79.0" \
  RELEASES="v3.79.0" run "released"
grep -q 'POST repos/o/r/issues/420/labels -f labels\[\]=autorelease: tagged' "${tmp}/writes" ||
  fail "released: #420 not labelled tagged ($(writes))"
grep -q 'DELETE repos/o/r/issues/420/labels/autorelease:%20pending' "${tmp}/writes" ||
  fail "released: #420 kept its pending label ($(writes))"

# Merged but not released yet: release-please still owns it.
PENDING_TSV='421\tgithub-actions[bot]\tchore(main): release 3.80.0\n' TAGS="" RELEASES="" \
  run "unreleased"
[[ -s "${tmp}/writes" ]] && fail "unreleased: wrote $(writes)"

# A tag with no release: the draft has not been created; leave it.
PENDING_TSV='421\tgithub-actions[bot]\tchore(main): release 3.80.0\n' TAGS="v3.80.0" RELEASES="" \
  run "tag only"
[[ -s "${tmp}/writes" ]] && fail "tag only: wrote $(writes)"

# Someone else's PR carrying the label is never touched.
PENDING_TSV='422\tmallory\tchore(main): release 3.79.0\n' TAGS="v3.79.0" RELEASES="v3.79.0" \
  run "foreign author"
[[ -s "${tmp}/writes" ]] && fail "foreign author: wrote $(writes)"

# A bot PR that is not a release PR is never touched.
PENDING_TSV='423\tgithub-actions[bot]\tchore: something else\n' TAGS="v3.79.0" \
  RELEASES="v3.79.0" run "not a release"
[[ -s "${tmp}/writes" ]] && fail "not a release: wrote $(writes)"

# Nothing pending, and an unreadable listing: both succeed and write nothing.
PENDING_TSV='' TAGS="" RELEASES="" run "nothing pending"
[[ -s "${tmp}/writes" ]] && fail "nothing pending: wrote $(writes)"
PENDING_TSV='' TAGS="" RELEASES="" LIST_FAILS=1 run "listing fails"
[[ -s "${tmp}/writes" ]] && fail "listing fails: wrote $(writes)"

if ((failures > 0)); then
  echo "${failures} case(s) failed" >&2
  exit 1
fi
echo "release-pr-mark-tagged: all cases passed"
