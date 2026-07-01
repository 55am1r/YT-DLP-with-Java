#!/usr/bin/env bash
# Build the whole app: React frontend -> Spring static resources -> runnable jar.
set -euo pipefail
export PATH="/opt/homebrew/bin:$PATH"

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> Building React frontend…"
cd "$DIR/web"
[ -d node_modules ] || npm install
npm run build

echo "==> Building Spring Boot jar…"
cd "$DIR/server"
sh gradlew bootJar

echo ""
echo "✓ Build complete. Start the server with:  bash start.sh"
