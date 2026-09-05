# Pinboard - the commands you actually run while working on the plugin.
#
# Thin wrappers over Gradle, which stays the source of truth. Every target here runs a command that
# already appears in README.md or .github/workflows/build.yml, so what you run locally and what CI
# runs cannot drift into meaning different things.
#
# Written for a POSIX shell - Git Bash on Windows - the same way ./gradlew is invoked everywhere
# else in this repository. Requires JDK 21.

GRADLE ?= ./gradlew

# Narrows `make test` to one class or method, e.g.
#   make test TEST='dev.pinboard.capture.AnchorRegistryTest'
#   make test TEST='*.AnchorRegistryTest.testAnchorFollowsCode'
TEST ?=

# Which IDE `make verify` checks against, in the verifier's <type>-<version> notation. Left empty
# it verifies against whatever IDE is at hand; CI passes IC-2025.3, PY-2025.2 and WS-2025.2 through
# this same property, one per matrix job, so a break names the IDE it broke on.
IDE ?=

.DEFAULT_GOAL := help
.PHONY: help build test run verify dist publish changelog ci clean

help: ## Show this help
	@echo 'Pinboard - make <target>'
	@echo
	@grep -hE '^[a-z][a-zA-Z0-9_-]*:.*?## ' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-11s\033[0m %s\n", $$1, $$2}'
	@echo
	@echo 'Variables: TEST=<pattern>  IDE=<type-version>  GRADLE=<wrapper>'

build: ## Compile and test
	$(GRADLE) build

test: ## Run the test suite (TEST=<pattern> narrows it to one class or method)
	$(GRADLE) test $(if $(TEST),--tests "$(TEST)",)

# The sandbox keeps the built plugin jar open, and on Windows that makes the next build fail with
# "user-mapped section open". Close the sandbox IDE before building again.
run: ## Launch a sandbox IDE with the plugin installed
	$(GRADLE) runIde

verify: ## JetBrains plugin verifier (IDE=IC-2025.3 picks one IDE)
	$(GRADLE) verifyPlugin $(if $(IDE),-PverifyIde=$(IDE),)

dist: ## Build the installable zip into build/distributions
	$(GRADLE) buildPlugin

# The token is read from the environment by build.gradle.kts and is never passed on the command
# line, where it would end up in the shell history.
publish: ## Publish to the JetBrains Marketplace (needs JETBRAINS_MARKETPLACE_TOKEN)
	@if [ -z "$$JETBRAINS_MARKETPLACE_TOKEN" ]; then \
		echo 'JETBRAINS_MARKETPLACE_TOKEN is not set - export it before publishing.' >&2; \
		exit 1; \
	fi
	$(GRADLE) publishPlugin

# CHANGELOG.md is generated. Move the Unreleased section under the current version with this,
# never by editing the file.
changelog: ## Roll the Unreleased section into the current version
	$(GRADLE) patchChangelog

ci: ## Everything CI runs: tests, the distribution, then the verifier
	$(GRADLE) test buildPlugin verifyPlugin $(if $(IDE),-PverifyIde=$(IDE),)

clean: ## Delete build output
	$(GRADLE) clean
