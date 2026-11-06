#!/usr/bin/env bash
# Run from the repository root. Set FRONTEND_DIR to require scanning a frontend checkout.
# Findings block by default. SECURITY_SCAN_ENFORCE=0 is diagnostic only; incomplete scans always fail.
set -euo pipefail
exec python3 "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/security_scan.py"
