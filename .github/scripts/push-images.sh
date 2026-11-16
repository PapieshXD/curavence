#!/usr/bin/env bash
set -euo pipefail
: "${VERSION:?VERSION is required}"
repository="${GITHUB_REPOSITORY,,}"
version="${VERSION#v}"
: > reports/digests.txt
for component in backend frontend imaging-ai; do
  image="ghcr.io/$repository-$component"
  docker push "$image:$version"
  docker push "$image:sha-$GITHUB_SHA"
  digest="$(docker inspect --format '{{index .RepoDigests 0}}' "$image:$version")"
  printf '%s\n' "$digest" >> reports/digests.txt
done
{
  echo '### Complete release: three images published'
  echo '```text'
  cat reports/digests.txt
  echo '```'
} >> "$GITHUB_STEP_SUMMARY"
