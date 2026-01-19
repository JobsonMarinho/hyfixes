#!/bin/bash
# HyFixes Bundle Builder
# Creates a CurseForge-compatible bundle.zip
#
# Usage:
#   ./build-bundle.sh          # Uses version from latest git tag
#   ./build-bundle.sh 1.6.0    # Uses specified version
#   ./build-bundle.sh v1.6.0   # Also works with 'v' prefix

set -e

# Change to script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Determine version
if [ -n "$1" ]; then
    # Version provided as argument (strip 'v' prefix if present)
    VERSION="${1#v}"
elif git describe --tags --exact-match HEAD 2>/dev/null; then
    # We're on a tagged commit - use the tag (strip 'v' prefix)
    VERSION="$(git describe --tags --exact-match HEAD | sed 's/^v//')"
elif git describe --tags 2>/dev/null; then
    # Use latest tag as base
    VERSION="$(git describe --tags --abbrev=0 | sed 's/^v//')"
else
    # Fallback
    VERSION="1.0.0-dev"
fi

BUNDLE_DIR="bundle"
OUTPUT_ZIP="hyfixes-bundle-${VERSION}.zip"

echo "========================================"
echo "  HyFixes Bundle Builder"
echo "  Version: ${VERSION}"
echo "========================================"

# Clean previous bundle
echo "[1/7] Cleaning previous bundle..."
rm -rf "${BUNDLE_DIR}/mods" "${BUNDLE_DIR}/earlyplugins" hyfixes-bundle-*.zip
mkdir -p "${BUNDLE_DIR}/mods" "${BUNDLE_DIR}/earlyplugins"

# Update bundle manifest version
echo "[2/7] Updating bundle manifest version..."
sed -i "s/\"Version\": \"[^\"]*\"/\"Version\": \"${VERSION}\"/" "${BUNDLE_DIR}/manifest.json"

# Build runtime plugin (passes version to Gradle)
echo "[3/7] Building runtime plugin..."
./gradlew build -Pversion="${VERSION}" --quiet
cp build/libs/hyfixes.jar "${BUNDLE_DIR}/mods/hyfixes-${VERSION}.jar"
echo "       -> mods/hyfixes-${VERSION}.jar"

# Build early plugin (passes version to Gradle)
echo "[4/7] Building early plugin..."
cd hyfixes-early
./gradlew build -Pversion="${VERSION}" --quiet
cd ..
cp hyfixes-early/build/libs/hyfixes-early.jar "${BUNDLE_DIR}/earlyplugins/hyfixes-early-${VERSION}.jar"
echo "       -> earlyplugins/hyfixes-early-${VERSION}.jar"

# Copy README
echo "[5/7] Adding documentation..."
cp CURSEFORGE.md "${BUNDLE_DIR}/README.md"

# Verify versions match
echo "[6/7] Verifying version consistency..."
echo "  Bundle manifest: $(grep -o '"Version": "[^"]*"' "${BUNDLE_DIR}/manifest.json")"
echo "  Runtime manifest: $(unzip -p build/libs/hyfixes.jar manifest.json 2>/dev/null | grep -o '"Version": "[^"]*"' || echo 'N/A')"
echo "  Early manifest: $(unzip -p hyfixes-early/build/libs/hyfixes-early.jar manifest.json 2>/dev/null | grep -o '"Version": "[^"]*"' || echo 'N/A')"

# Create ZIP
echo "[7/7] Creating bundle ZIP..."
cd "${BUNDLE_DIR}"
zip -r "../${OUTPUT_ZIP}" manifest.json mods/ earlyplugins/ README.md
cd ..

echo ""
echo "========================================"
echo "  Bundle created: ${OUTPUT_ZIP}"
echo "========================================"
echo ""
echo "Contents:"
unzip -l "${OUTPUT_ZIP}"
echo ""
echo "Ready for CurseForge upload!"
