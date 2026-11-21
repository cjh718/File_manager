# Commit the remaining spec file and push

## Context

Routine "review, commit all, push" request. Review found the working tree clean except one untracked file the studio generated: `spec/self-service-build-test-no-ai-credits/plan.md` (the approved plan for the ship.sh / no-credit self-service task). All code is already committed at `dcd647d`; branch `feat/folio-android` is in sync with `origin`. The user wants every non-gitignored file (including spec/) committed and pushed.

## Steps

1. `git add -A` (stages the untracked `spec/self-service-build-test-no-ai-credits/plan.md`; nothing else is pending).
2. Commit with a conventional message, e.g. `docs: record self-service build plan in spec/`, ending with `Co-Authored-By: Claude Code <noreply@anthropic.com>`.
3. `git push origin feat/folio-android` (origin is configured: https://github.com/cjh718/File_manager.git).
4. Confirm `git status` is clean and the branch matches origin.

## Verification
- `git status --short` shows nothing pending after the commit.
- `git log --oneline -1` shows the new commit; `git ls-remote origin refs/heads/feat/folio-android` matches HEAD.
- No code/build changes involved — no build or upload needed.