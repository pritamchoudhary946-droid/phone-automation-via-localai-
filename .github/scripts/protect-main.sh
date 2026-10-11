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

body=$(cat <<JSON
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
)

response=$(curl -sS -w $'\n%{http_code}' -X PUT \
  -H "Authorization: Bearer ${GITHUB_TOKEN}" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  "https://api.github.com/repos/${REPO}/branches/main/protection" \
  -d "$body")

code=$(printf '%s' "$response" | tail -n1)
payload=$(printf '%s' "$response" | sed '$d')

if [ "$code" = "200" ]; then
  echo "OK: ${REPO}@main now requires the '${CHECK}' status check."
  exit 0
fi

echo "FAILED (HTTP ${code}): could not set branch protection on ${REPO}@main." >&2
printf '%s\n' "$payload" >&2
echo >&2
echo "If this says 'Resource not accessible by integration', the token is a GitHub" >&2
echo "App / integration token, which cannot edit branch protection. Re-run with a" >&2
echo "personal access token from an account with admin on the repo, e.g.:" >&2
echo "  GITHUB_TOKEN=<personal-admin-pat> REPO=${REPO} bash $0" >&2
echo >&2
echo "Or set it by hand: Settings > Branches > add a rule for 'main' >" >&2
echo "Require status checks to pass > add '${CHECK}'." >&2
exit 1

