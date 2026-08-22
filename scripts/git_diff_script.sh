#!/usr/bin/env bash
# ==============================================================================
# merge-demo.sh
# Demonstrates a NON-CONFLICTING three-way merge in Git, starting from scratch.
#
# Story:
#   - Branch C: initial commit
#   - Branch D: checked out from C, appends a line to file.txt
#   - Branch B: checked out from C, adds a brand new file extra.txt
#   - Merge D into B -> non-conflicting, auto-merged by Git
#
# Usage:
#   bash merge-demo.sh
# ==============================================================================

set -euo pipefail

DEMO_DIR="merge-demo"

echo "=============================================="
echo "STEP 0: Clean slate"
echo "=============================================="
rm -rf "$DEMO_DIR"
mkdir "$DEMO_DIR"
cd "$DEMO_DIR"

echo
echo "=============================================="
echo "STEP 1: git init + config"
echo "=============================================="
git init

echo
echo "=============================================="
echo "STEP 2: First commit on branch C"
echo "=============================================="
echo "line1" > file.txt
git add .
git commit -q -m "C: initial commit"
git branch -M C
git log --oneline

echo
echo "=============================================="
echo "STEP 3: Create branch D from C, add a commit"
echo "=============================================="
git checkout -q -b D C
echo "line2 from D" >> file.txt
git add .
git commit -q -m "D: add line2 to file.txt"
echo "--- D history ---"
git log --oneline

echo
echo "=============================================="
echo "STEP 4: Create branch B from C, add a DIFFERENT commit"
echo "=============================================="
git checkout -q -b B C
echo "extra.txt content from B" > extra.txt
git add .
git commit -q -m "B: add extra.txt"
echo "--- B history ---"
git log --oneline
echo "--- files on B before merge ---"
ls

echo
echo "=============================================="
echo "STEP 5: Merge D into B (non-conflicting)"
echo "=============================================="
echo "--- file.txt on B, before merge ---"
cat file.txt
echo "-------------------------------------"
git merge D -m "Merge D into B"

echo
echo "=============================================="
echo "STEP 6: Inspect the result"
echo "=============================================="
echo "--- log graph (all branches) ---"
git log --oneline --graph --all
echo
echo "--- final file.txt content (union of both diffs) ---"
cat file.txt
echo
echo "--- final directory listing ---"
ls
echo
echo "--- merge commit + its two parents ---"
git log -1 --format="merge commit: %H%nparent 1 (B):  %p" HEAD
echo "(full parent list below)"
git log -1 --format="%H %P" HEAD

echo
echo "=============================================="
echo "Done. Repo left at: $(pwd)"
echo "=============================================="
