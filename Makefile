# Pinboard - the commands you actually run while working on the plugin.
#
# Thin wrappers over Gradle, which stays the source of truth. Every target runs a command that
# already appears in README.md or .github/workflows/build.yml, so what you run locally and what CI
# runs cannot drift into meaning different things. Requires JDK 21.
#
# Recipes stay free of shell-specific syntax on purpose - see the note on GRADLE below.

# Which shell runs the recipes is pinned here rather than left to make, and the Gradle wrapper is
# spelled to match it.
#
# On Windows, make left to itself picks cmd.exe or a POSIX sh depending on what it finds on PATH -
# not on the shell you typed `make` into, and not consistently between runs on one machine. cmd
# reads the leading dot of "./gradlew" as a command of its own and fails, so guessing wrong breaks
# every target. Asking make which shell it holds does not settle it either: it reported sh while
# still running the recipe through cmd. Naming the shell removes the guess.
#
# Nothing below needs a POSIX shell, which is what makes cmd an acceptable answer here.
ifeq ($(OS),Windows_NT)
  SHELL := cmd.exe
  GRADLE ?= .\gradlew.bat
else
  GRADLE ?= ./gradlew
endif

# Narrows `make test` to one class or method, e.g.
#   make test TEST="dev.pinboard.capture.AnchorRegistryTest"
#   make test TEST="*.AnchorRegistryTest.testAnchorFollowsCode"
TEST ?=

# Which IDE `make verify` checks against, in the verifier's <type>-<version> notation. Left empty it
# verifies against whatever IDE is at hand; CI passes IC-2025.3, PY-2025.2 and WS-2025.2 through
# this same property, one per matrix job, so a break names the IDE it broke on.
IDE ?=

# The version `make release` ships. There is no positional form: make reads a bare 0.0.3 as another
# target to build and fails looking for a rule to make it.
VERSION ?=

.DEFAULT_GOAL := help
.PHONY: help build test run verify dist publish changelog release ci clean

# Written out by hand rather than generated from the target comments, because generating it needs
# grep and awk and this has to print the same under cmd.exe. Keep it in step with the targets.
# No alignment padding either: cmd keeps the spacing and sh collapses it, so it can only look
# deliberate in one of them.
help:
	@echo Pinboard - run make TARGET, where TARGET is one of:
	@echo build - compile and test
	@echo test - run the test suite. TEST=PATTERN narrows it to one class or method
	@echo run - launch a sandbox IDE with the plugin installed
	@echo verify - JetBrains plugin verifier. IDE=IC-2025.3 checks against one IDE
	@echo dist - build the installable zip into build/distributions
	@echo publish - publish the current version to the JetBrains Marketplace
	@echo changelog - roll the Unreleased section into the current version
	@echo release - VERSION=0.0.3 ships it: bump, changelog, test, commit, tag, publish, push, GitHub release
	@echo ci - everything CI runs: tests, the distribution, then the verifier
	@echo clean - delete build output

build:
	$(GRADLE) build

test:
	$(GRADLE) test $(if $(TEST),--tests "$(TEST)",)

# The sandbox keeps the built plugin jar open, and on Windows that makes the next build fail with
# "user-mapped section open". Close the sandbox IDE before building again.
run:
	$(GRADLE) runIde

verify:
	$(GRADLE) verifyPlugin $(if $(IDE),-PverifyIde=$(IDE),)

dist:
	$(GRADLE) buildPlugin

# The token is read from the environment by build.gradle.kts and never passed on the command line,
# where it would end up in the shell history. The guard is a make conditional rather than a shell
# `test` so that it reads the same under cmd.exe, and it checks only that a value is present -
# nothing here ever prints it.
publish:
	@$(if $(JETBRAINS_MARKETPLACE_TOKEN),,$(error JETBRAINS_MARKETPLACE_TOKEN is not set - export it before publishing))
	$(GRADLE) publishPlugin

# CHANGELOG.md is generated. Move the Unreleased section under the current version with this, never
# by editing the file.
#
# Bump the version first. Running this twice at one version folds Unreleased into a section that
# already exists and the new entries are lost without a word - recover them from git if it happens.
changelog:
	$(GRADLE) patchChangelog

# The whole release, from a version number to a published plugin:
#
#   make release VERSION=0.0.3
#
# Write what changed under `## [Unreleased]` in CHANGELOG.md first. That section is the release
# notes: it becomes the [0.0.3] section here, the description on the Marketplace page, and the body
# of the GitHub release. Releasing with it empty ships a version with nothing to say for itself.
#
# Each Gradle line is its own invocation on purpose. project.version is read when the build is
# configured, so patchChangelog and publishPlugin can only see the new number from an invocation
# later than the one that wrote it.
#
# The commit and the tag are made before the upload but pushed after it. An upload that fails then
# leaves a local commit to retry or reset, instead of a tag on the remote announcing a release that
# never reached the Marketplace. If the push is what fails, everything is already published and
# committed - just push again.
#
# The GitHub release comes last, because it can only point at a tag the remote already has. It is
# also the only step that is safe to repeat by hand, so if it is what fails, the two lines under it
# are the whole recovery:
#
#   .\gradlew.bat writeReleaseNotes
#   gh release create v0.0.3 --title "Pinboard 0.0.3" --notes-file build/release-notes.md --verify-tag
#
# gh is checked at the top rather than here. Finding out that it is missing after the plugin is
# published, tagged and pushed leaves the release half-announced, which is the one state worth
# spending an early second to avoid.
release:
	@$(if $(VERSION),,$(error VERSION is not set - run make release VERSION=0.0.3))
	@$(if $(JETBRAINS_MARKETPLACE_TOKEN),,$(error JETBRAINS_MARKETPLACE_TOKEN is not set - export it before releasing))
	@echo Releasing $(VERSION). The working tree must be clean and on the branch you release from.
	git diff --quiet HEAD
	gh auth status
	$(GRADLE) checkReleaseNotes
	$(GRADLE) setVersion -PnewVersion=$(VERSION)
	$(GRADLE) patchChangelog
	$(MAKE) ci
	git add gradle.properties CHANGELOG.md
	git commit -m "feat: release $(VERSION)"
	git tag -a v$(VERSION) -m "Pinboard $(VERSION)"
	$(GRADLE) publishPlugin
	git push --follow-tags origin main
	$(GRADLE) writeReleaseNotes
	gh release create v$(VERSION) --title "Pinboard $(VERSION)" --notes-file build/release-notes.md --verify-tag

ci:
	$(GRADLE) test buildPlugin verifyPlugin $(if $(IDE),-PverifyIde=$(IDE),)

clean:
	$(GRADLE) clean
