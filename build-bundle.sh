#!/bin/bash
# HyFixes Bundle Builder
# Creates a CurseForge-compatible bundle.zip

set -e

# Change to script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

VERSION="1.1.0"
BUNDLE_DIR="bundle"
OUTPUT_ZIP="hyfixes-bundle-${VERSION}.zip"

echo "========================================"
echo "  HyFixes Bundle Builder v${VERSION}"
echo "========================================"

# Clean previous bundle
echo "[1/6] Cleaning previous bundle..."
rm -rf "${BUNDLE_DIR}/mods" "${BUNDLE_DIR}/earlyplugins" "${OUTPUT_ZIP}"
mkdir -p "${BUNDLE_DIR}/mods" "${BUNDLE_DIR}/earlyplugins"

# Build runtime plugin
echo "[2/6] Building runtime plugin..."
./gradlew build --quiet
cp build/libs/hyfixes.jar "${BUNDLE_DIR}/mods/hyfixes-${VERSION}.jar"
echo "       -> mods/hyfixes-${VERSION}.jar"

# Build early plugin
echo "[3/6] Building early plugin..."
cd hyfixes-early
./gradlew build --quiet
cd ..
cp hyfixes-early/build/libs/hyfixes-early-*.jar "${BUNDLE_DIR}/earlyplugins/hyfixes-early-${VERSION}.jar"
echo "       -> earlyplugins/hyfixes-early-${VERSION}.jar"

# Copy README
echo "[4/6] Adding documentation..."
cp CURSEFORGE.md "${BUNDLE_DIR}/README.md"

# Update manifest version
echo "[5/6] Updating manifest version..."
sed -i "s/\"Version\": \"[^\"]*\"/\"Version\": \"${VERSION}\"/" "${BUNDLE_DIR}/manifest.json"

# Create ZIP
echo "[6/6] Creating bundle ZIP..."
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
