#!/bin/bash
# Generate changelog from commits using OpenRouter API
# Requires: OPENROUTER_API_KEY and OPENROUTER_MODEL environment variables

set -e

# Get the last tag (excluding the current one)
LAST_TAG=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null || echo "")

# Get commits since last tag, or all commits if no previous tag
if [ -z "$LAST_TAG" ]; then
  echo "No previous tag found, using recent commits" >&2
  COMMITS=$(git log --oneline --no-merges -50)
else
  echo "Getting commits since $LAST_TAG" >&2
  COMMITS=$(git log --oneline --no-merges ${LAST_TAG}..HEAD)
fi

# If no commits found, provide default message
if [ -z "$COMMITS" ]; then
  echo "## Changes"
  echo ""
  echo "- Minor updates and improvements"
  exit 0
fi

# Check if API key is set
if [ -z "$OPENROUTER_API_KEY" ]; then
  echo "Warning: OPENROUTER_API_KEY not set, using commit list as changelog" >&2
  echo "## Changes"
  echo ""
  echo "$COMMITS" | while read -r line; do
    echo "- ${line#* }"  # Remove commit hash prefix
  done
  exit 0
fi

# Default model if not specified
MODEL="${OPENROUTER_MODEL:-anthropic/claude-sonnet-4}"

# Escape commits for JSON (handle newlines, quotes, backslashes)
COMMITS_ESCAPED=$(echo "$COMMITS" | jq -Rs .)

# Build the prompt
PROMPT="Generate a changelog for a Hytale server plugin release called HyFixes. Format as markdown with sections for Features, Fixes, and Changes as needed. Be concise and user-friendly. Focus on what changed, not commit hashes. If there are no commits in a category, omit that section. Here are the commits since last release:\n\n${COMMITS_ESCAPED}"

# Call OpenRouter API (OpenAI-compatible format)
RESPONSE=$(curl -s https://openrouter.ai/api/v1/chat/completions \
  -H "Authorization: Bearer $OPENROUTER_API_KEY" \
  -H "HTTP-Referer: https://github.com/John-Willikers/hyfixes" \
  -H "X-Title: HyFixes Release Changelog" \
  -H "Content-Type: application/json" \
  -d "{
    \"model\": \"$MODEL\",
    \"max_tokens\": 1024,
    \"messages\": [{
      \"role\": \"user\",
      \"content\": \"$PROMPT\"
    }]
  }")

# Extract the text content from the response (OpenAI format)
CHANGELOG=$(echo "$RESPONSE" | jq -r '.choices[0].message.content // empty')

# Check if we got a valid response
if [ -z "$CHANGELOG" ]; then
  echo "Warning: Failed to generate changelog from API, using fallback" >&2
  # Check for error message
  ERROR=$(echo "$RESPONSE" | jq -r '.error.message // empty')
  if [ -n "$ERROR" ]; then
    echo "API Error: $ERROR" >&2
  fi
  echo "## Changes"
  echo ""
  echo "$COMMITS" | while read -r line; do
    echo "- ${line#* }"  # Remove commit hash prefix
  done
  exit 0
fi

echo "$CHANGELOG"
