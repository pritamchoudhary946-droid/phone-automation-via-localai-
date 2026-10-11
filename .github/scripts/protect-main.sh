#!/usr/bin/env bash
#
# Enable branch protection on `main` and require the "Verify (unit tests + APK)"
# status check before merge. Run once, by a repo admin:
#
#   GITHUB_TOKEN=<admin-token> REPO=pritamchoudhary946-droid/phone-automation-via-localai- \
#     bash .github/scripts/protect-main.sh
#
# Why: the autonomy loop self-merged a red PR (#44 merged 35s after its check
# failed), which broke `main`. Requiring the check makes that impossible.
set -euo pipefail

: "${GITHUB_TOKEN:?set GITHUB_TOKEN to a token with admin rights on the repo}"
: "${REPO:?set REPO as owner/name}"

CHECK="Verify (unit tests + APK)"

curl -sS -X PUT \
  -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "https://api.github.com/repos/${REPO}/branches/main/protection" \
  -d @- <<JSON
{
  "required_status_checks": {
    "strict": true,
    "contexts": ["${CHECK}"]
  },
  "enforce_admins": false,
  "required_pull_request_reviews": null,
  "restrictions": null,
  "allow_force_pushes": false,
  "allow_deletions": false
}
JSON

echo
echo "Branch protection on ${REPO}@main now requires: ${CHECK}"
