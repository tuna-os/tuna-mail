#!/usr/bin/env bash
# Tests for tools/tunamail/check-touchpoints.sh. Builds a throwaway git repository with an
# "upstream" branch and a fork branch, then runs a copy of the script against it.
#
# Usage: tools/tunamail/test-check-touchpoints.sh
set -uo pipefail

here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/check-touchpoints.sh"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

failures=0
out=""
status=0

# new_repo <touchpoints.txt content>: a repo whose "upstream" branch has upstream.txt and other.txt,
# with the fork branch checked out on top of it.
new_repo() {
    repo="$tmp/repo"
    rm -rf "$repo"
    mkdir -p "$repo/tools/tunamail"
    cp "$script" "$repo/tools/tunamail/check-touchpoints.sh"
    printf '%s\n' "$1" > "$repo/tools/tunamail/touchpoints.txt"
    git -C "$repo" init -q -b upstream
    git -C "$repo" config user.email test@example.invalid
    git -C "$repo" config user.name test
    git -C "$repo" config commit.gpgsign false
    echo one > "$repo/upstream.txt"
    echo two > "$repo/other.txt"
    git -C "$repo" add -A
    git -C "$repo" commit -q -m upstream
    git -C "$repo" checkout -q -b fork
}

commit_all() {
    git -C "$repo" add -A
    git -C "$repo" commit -q -m fork
}

run_check() {
    out=$(cd "$repo" && tools/tunamail/check-touchpoints.sh upstream 2>&1)
    status=$?
}

expect() {
    local name=$1 want_status=$2 want_text=$3
    if [ "$status" -ne "$want_status" ] || ! grep -qF -- "$want_text" <<<"$out"; then
        echo "FAIL: $name (exit $status, wanted $want_status; wanted output to contain: $want_text)"
        sed 's/^/    /' <<<"$out"
        failures=$((failures + 1))
    else
        echo "ok: $name"
    fi
}

expect_absent() {
    local name=$1 text=$2
    if grep -qF -- "$text" <<<"$out"; then
        echo "FAIL: $name (output must not contain: $text)"
        sed 's/^/    /' <<<"$out"
        failures=$((failures + 1))
    fi
}

new_repo "upstream.txt"
echo changed > "$repo/upstream.txt"
commit_all
run_check
expect "a modified file that is listed passes" 0 "touchpoints ok: 1 upstream files modified, all listed"

new_repo "upstream.txt"
echo changed > "$repo/other.txt"
commit_all
run_check
expect "a modified file that is not listed fails" 1 "  other.txt"
expect "the failure points at the list" 1 "tools/tunamail/touchpoints.txt"

new_repo "upstream.txt"
echo new > "$repo/fork-only.txt"
commit_all
run_check
expect "a file the fork adds is not a touchpoint" 0 "touchpoints ok: 0 upstream files modified, all listed"

new_repo "upstream.txt"
git -C "$repo" rm -q other.txt
commit_all
run_check
expect "a file the fork deletes counts as modified" 1 "  other.txt"

new_repo "# a comment

upstream.txt

# another comment"
echo changed > "$repo/upstream.txt"
commit_all
run_check
expect "comments and blank lines in the list are ignored" 0 "touchpoints ok: 1 upstream files modified, all listed"

new_repo "upstream.txt
other.txt"
echo changed > "$repo/upstream.txt"
commit_all
run_check
expect "a listed file that is no longer modified is reported" 0 "no longer modified (remove them)"
expect "the stale file is named" 0 "  other.txt"

new_repo "upstream.txt"
echo changed > "$repo/upstream.txt"
echo changed > "$repo/other.txt"
commit_all
run_check
expect "listed files do not hide an unlisted one" 1 "  other.txt"
expect_absent "the listed file is not reported as unlisted" "  upstream.txt"

new_repo "# nothing listed"
git -C "$repo" commit -q --allow-empty -m fork
run_check
expect "a fork with no changes passes" 0 "touchpoints ok: 0 upstream files modified, all listed"

if [ "$failures" -ne 0 ]; then
    echo "$failures check(s) failed"
    exit 1
fi
echo "all check-touchpoints tests passed"
