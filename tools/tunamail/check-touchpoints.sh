#!/usr/bin/env bash
# Fail when the Tuna Mail fork modifies an upstream file that tools/tunamail/touchpoints.txt
# does not list. Every listed file is a place an upstream merge can conflict, so the list is
# kept short on purpose and grows only by a reviewed edit.
#
# Usage: tools/tunamail/check-touchpoints.sh [upstream-ref]   (default: upstream/main)
# Files the fork adds (fork-only code) are not touchpoints and never fail. Needs the full
# history of both sides (no shallow clone), since it diffs against the merge base.
set -euo pipefail

ref=${1:-upstream/main}
root=$(git rev-parse --show-toplevel)
list="$root/tools/tunamail/touchpoints.txt"

modified=$(git -C "$root" diff --name-status "$ref"...HEAD \
    | awk '$1 != "A" { print $NF }' | sort -u)
listed=$(sed '/^$/d; /^#/d' "$list" | sort -u)

unlisted=$(comm -23 <(printf '%s\n' "$modified" | sed '/^$/d') <(printf '%s\n' "$listed"))
stale=$(comm -13 <(printf '%s\n' "$modified" | sed '/^$/d') <(printf '%s\n' "$listed"))

if [ -n "$stale" ]; then
    echo "Listed in tools/tunamail/touchpoints.txt but no longer modified (remove them):"
    printf '  %s\n' $stale
fi
if [ -n "$unlisted" ]; then
    echo "The fork modifies upstream files not listed in tools/tunamail/touchpoints.txt:"
    printf '  %s\n' $unlisted
    echo "Either keep the change upstream-shaped and send it upstream, or add the file to"
    echo "tools/tunamail/touchpoints.txt with a reason (see docs/tunamail/MAINTAINING.md)."
    exit 1
fi
echo "touchpoints ok: $(printf '%s\n' "$modified" | sed '/^$/d' | wc -l) upstream files modified, all listed"
