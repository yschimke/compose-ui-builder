#!/usr/bin/env bash
# Mark a merged release PR `autorelease: tagged` once its release exists, however it was cut.
#
# release-please moves a merged release PR from `autorelease: pending` to `autorelease: tagged`
# when IT creates the GitHub release. A release finished any other way -- by hand, or through the
# `publish_tag` recovery path after release-please could not create it -- leaves the PR pending,
# and release-please then wedges on every push to main:
#
#   - the release half finds the pending PR and tries to create its release again, which fails
#     (`Resource not accessible by integration` for v3.79.0, whose range added a workflow file);
#   - the PR half aborts with "There are untagged, merged release PRs outstanding", so no next
#     release PR is ever opened.
#
# That is how every push after v3.79.0 failed and no v3.80.0 release PR appeared. This runs
# before release-please and relabels such a PR, so a release cut out of band costs nothing more.
#
# Only release-please's own PRs are touched: merged, authored by `github-actions[bot]`, titled
# `chore(main): release <version>`, and only when BOTH the `v<version>` tag and a release for it
# (draft or published) exist -- the state release-please itself would have left. Fail open: an
# unreadable answer leaves the PR as it is, and the step never fails the run.
#
# Usage: GITHUB_REPOSITORY=owner/repo GH_TOKEN=… release-pr-mark-tagged.sh
set -uo pipefail

REPO="${GITHUB_REPOSITORY:?GITHUB_REPOSITORY required}"
PENDING="autorelease: pending"
TAGGED="autorelease: tagged"
BOT="github-actions[bot]"

pending="$(gh api "repos/${REPO}/issues?state=closed&labels=autorelease:%20pending&per_page=100" \
  --jq '.[] | select(.pull_request.merged_at != null) | [.number, .user.login, .title] | @tsv' \
  2>/dev/null)" || {
  echo "::warning::could not list pending release PRs; leaving them alone"
  exit 0
}

if [[ -z "${pending}" ]]; then
  echo "no merged release PR is pending"
  exit 0
fi

while IFS=$'\t' read -r number author title; do
  [[ -n "${number}" ]] || continue
  if [[ "${author}" != "${BOT}" ]]; then
    echo "::notice::#${number} is by ${author}, not ${BOT}; leaving it alone"
    continue
  fi
  if [[ ! "${title}" =~ ^chore\(main\):\ release\ ([0-9][0-9A-Za-z.+-]*)$ ]]; then
    echo "::notice::#${number} is titled \"${title}\", not a release; leaving it alone"
    continue
  fi
  tag="v${BASH_REMATCH[1]}"
  if ! gh api "repos/${REPO}/git/ref/tags/${tag}" > /dev/null 2>&1; then
    echo "#${number}: ${tag} has no tag yet; release-please still owns it"
    continue
  fi
  # `gh release view` resolves drafts, which `releases/tags/<tag>` does not.
  if ! gh release view "${tag}" --repo "${REPO}" --json tagName > /dev/null 2>&1; then
    echo "#${number}: ${tag} has a tag but no release yet; release-please still owns it"
    continue
  fi
  if gh api --method POST "repos/${REPO}/issues/${number}/labels" -f "labels[]=${TAGGED}" \
    > /dev/null 2>&1 &&
    gh api --method DELETE "repos/${REPO}/issues/${number}/labels/autorelease:%20pending" \
      > /dev/null 2>&1; then
    echo "#${number}: ${tag} was released outside release-please; marked ${TAGGED}"
  else
    echo "::warning::could not move #${number} from ${PENDING} to ${TAGGED}"
  fi
done <<< "${pending}"
exit 0
