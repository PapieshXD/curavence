#!/usr/bin/env bash
set -euo pipefail
: "${VERSION:?VERSION is required}"
repository="${GITHUB_REPOSITORY,,}"
version="${VERSION#v}"
mkdir -p reports
cleanup() {
  docker rm -f ci-frontend ci-imaging >/dev/null 2>&1 || true
}
trap cleanup EXIT
for component in backend frontend imaging-ai; do
  context="$component"
  if [[ "$component" == imaging-ai ]]; then context=imaging-ai-service; fi
  image="ghcr.io/$repository-$component"
  docker buildx build --platform linux/amd64 --load \
    --label "org.opencontainers.image.source=https://github.com/$GITHUB_REPOSITORY" \
    --label "org.opencontainers.image.revision=$GITHUB_SHA" \
    --label "org.opencontainers.image.version=$version" \
    -t "$image:$version" -t "$image:sha-$GITHUB_SHA" "$context"
done
backend="ghcr.io/$repository-backend:$version"
docker run --rm --entrypoint java "$backend" -version
docker run --rm --entrypoint sh "$backend" -c 'test -s /app/app.jar && test "$(id -u)" != 0'
docker run -d --name ci-frontend -p 127.0.0.1:18080:8080 "ghcr.io/$repository-frontend:$version"
docker run -d --name ci-imaging -p 127.0.0.1:18090:8090 "ghcr.io/$repository-imaging-ai:$version"
docker exec ci-imaging python -c 'import wfdb, pyedflib, scipy'
curl --fail --retry 30 --retry-connrefused --retry-delay 2 http://127.0.0.1:18080/ -o reports/index.html
curl --fail http://127.0.0.1:18080/patient/results -o reports/spa.html
cmp reports/index.html reports/spa.html
curl --fail --retry 30 --retry-connrefused --retry-delay 2 http://127.0.0.1:18090/health -o reports/imaging-health.json
python -c 'import json; assert json.load(open("reports/imaging-health.json"))["status"] == "ok"'
for component in backend frontend imaging-ai; do
  image="ghcr.io/$repository-$component:$version"
  trivy image --disable-telemetry --no-progress --scanners vuln --format json --output "reports/trivy-$component.json" "$image"
  python .github/scripts/quality.py gate trivy "reports/trivy-$component.json" --strict
  trivy image --disable-telemetry --no-progress --format cyclonedx --output "reports/$component.cdx.json" "$image"
done
