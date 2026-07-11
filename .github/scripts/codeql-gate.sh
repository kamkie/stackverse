#!/usr/bin/env bash
set -eu

case "${1:-}" in
  classify)
    changed_files="${2:?changed-files path is required}"
    [ -s "$changed_files" ] || {
      echo "changed-files input is empty" >&2
      exit 2
    }
    if grep -Evq '\.md$|^\.github/CODEOWNERS$' "$changed_files"; then
      echo true
    else
      echo false
    fi
    ;;
  blocking-alerts)
    jq '[.[] | select(
      .rule.security_severity_level == "critical" or
      .rule.security_severity_level == "high" or
      ((.rule.security_severity_level == null or .rule.security_severity_level == "none") and .rule.severity == "error")
    )]'
    ;;
  *)
    echo "usage: $0 classify <changed-files> | blocking-alerts" >&2
    exit 2
    ;;
esac
