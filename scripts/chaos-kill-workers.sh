#!/bin/bash
# Chaos test companion script
# Kills 50% of worker containers to test system resilience
# Usage: Run this 2 minutes into the chaos load test

set -e

echo "=== Flik Chaos Test: Killing 50% of Workers ==="
echo "Waiting 120 seconds for baseline to stabilize..."
sleep 120

echo "Killing 50% of text workers..."
TEXT_CONTAINERS=$(docker compose ps -q worker-text | head -n $(( $(docker compose ps -q worker-text | wc -l) / 2 )))
for c in $TEXT_CONTAINERS; do
    echo "  Stopping container $c"
    docker stop "$c"
done

echo "Killing 50% of image workers..."
IMAGE_CONTAINERS=$(docker compose ps -q worker-image | head -n $(( $(docker compose ps -q worker-image | wc -l) / 2 )))
for c in $IMAGE_CONTAINERS; do
    echo "  Stopping container $c"
    docker stop "$c"
done

echo "=== Workers killed. Monitoring recovery... ==="
echo "Remaining workers:"
docker compose ps | grep worker

echo ""
echo "Waiting 120 seconds for autoscaler to recover..."
sleep 120

echo "=== Post-recovery state ==="
docker compose ps | grep worker
echo ""
echo "Queue depths:"
curl -s -u flik:flik http://localhost:15672/api/queues/%2F/ | python3 -c "
import json, sys
queues = json.load(sys.stdin)
for q in queues:
    if q['name'].startswith('flik.tasks'):
        print(f\"  {q['name']}: {q['messages']} messages\")
" 2>/dev/null || echo "  (could not fetch queue info)"

echo "=== Chaos test companion complete ==="
