# Pinboard - the commands you actually run while working on the plugin.
#
# Thin wrappers over Gradle, which stays the source of truth. Every target runs a command that
# already appears in README.md or .github/workflows/build.yml, so what you run locally and what CI
# runs cannot drift into meaning different things. Requires JDK 21.
#
# Recipes stay free of shell-specific syntax on purpose - see the note on GRADLE below.

# How the Gradle wrapper has to be spelled depends on the shell make picked, and on Windows that is
# not always the shell you typed `make` into: GNU make falls back to cmd.exe when it finds no sh on
# PATH, even from Git Bash, and cmd reads the leading dot of "./gradlew" as a command of its own.
# Both spellings were run under both shells to confirm each works only where it is used here.
ifeq ($(findstring sh,$(notdir $(SHELL))),sh)
  GRADLE ?= ./gradlew
else
  GRADLE ?= .\gradlew.bat
endif

# Narrows `make test` to one class or method, e.g.
#   make test TEST="dev.pinboard.capture.AnchorRegistryTest"
#   make test TEST="*.AnchorRegistryTest.testAnchorFollowsCode"
TEST ?=

# Which IDE `make verify` checks against, in the verifier's <type>-<version> notation. Left empty it
# verifies against whatever IDE is at hand; CI passes IC-2025.3, PY-2025.2 and WS-2025.2 through
# this same property, one per matrix job, so a break names the IDE it broke on.
IDE ?=

.DEFAULT_GOAL := help
.PHONY: help build test run verify dist publish changelog ci clean

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
	@echo publish - publish to the JetBrains Marketplace
	@echo changelog - roll the Unreleased section into the current version
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
changelog:
	$(GRADLE) patchChangelog

ci:
	$(GRADLE) test buildPlugin verifyPlugin $(if $(IDE),-PverifyIde=$(IDE),)

clean:
	$(GRADLE) clean
