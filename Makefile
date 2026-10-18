# Hatch — automated checks for THIS box (2 vCPU / 3.6 GB VPS that also runs prod containers).
# Every heavy process (Gradle, headless Firefox, lint jars, npm) goes through flock /tmp/hatch-heavy.lock
# so at most one exists at a time. Memory caps live in gradle.properties; never raise them here.
#
#   make check        lint + unit + headless           (serial, < 1.3 GB peak)
#   make check-full   check + apk + android-lint
#   make headless     all personas + negative controls -> build/harness-reports/
#   make setup        Firefox 158 + geckodriver + harness npm deps (idempotent)
SHELL := /bin/bash
.SHELLFLAGS := -eu -o pipefail -c
.DEFAULT_GOAL := help

LOCK      := flock /tmp/hatch-heavy.lock
export JAVA_HOME    ?= /opt/android-studio/jbr
export ANDROID_HOME ?= $(HOME)/Android/Sdk
GRADLE    := ./gradlew --no-daemon --max-workers=1 --console=plain
NODE      := node
HARNESS   := harness
HEADLESS  := tool/headless
REPORTS   := build/harness-reports
EXT_DIR   := app/src/main/assets/extension
KTLINT    := tool/bin/ktlint.jar
DETEKT    := tool/bin/detekt-cli.jar
JAVA      := $(JAVA_HOME)/bin/java
WEB_EXT   := $(shell command -v web-ext 2>/dev/null || { [ -x $(HARNESS)/node_modules/.bin/web-ext ] && echo $(HARNESS)/node_modules/.bin/web-ext; } || true)
PERSONAS  ?= all

.PHONY: help setup expectations lint lint-kotlin lint-ext unit unit-persona unit-harness unit-ext vendor vendor-check headless headless-noext apk android-lint check check-full clean-reports

help:
	@grep -E '^[a-z][a-z-]*:.*## ' $(MAKEFILE_LIST) | sed 's/:.*## /\t/' | column -t -s $$'\t'

setup: ## Firefox 158.0 + geckodriver 0.37.1 into ~/opt and `npm ci` in harness/ (idempotent)
	$(HEADLESS)/setup.sh

expectations: ## regenerate harness/personas/*.json from personas/*.json
	$(NODE) $(HEADLESS)/gen-expectations.mjs

lint: lint-kotlin lint-ext ## ktlint + detekt (jars in tool/bin, -Xmx512m) + web-ext lint --self-hosted
	@$(NODE) $(HEADLESS)/gen-expectations.mjs --check

lint-kotlin:
	@if [ -f $(KTLINT) ]; then \
	  echo ">> ktlint"; $(LOCK) $(JAVA) -Xmx512m -jar $(KTLINT) "persona/src/**/*.kt" "app/src/**/*.kt" "*.kts"; \
	else echo "!! $(KTLINT) missing: see tool/bin/README.md (ktlint skipped)"; fi
	@if [ -f $(DETEKT) ]; then \
	  echo ">> detekt"; $(LOCK) $(JAVA) -Xmx512m -jar $(DETEKT) --input persona/src,app/src --build-upon-default-config $$( [ -f detekt.yml ] && echo --config detekt.yml ); \
	else echo "!! $(DETEKT) missing: see tool/bin/README.md (detekt skipped)"; fi

lint-ext:
	@if [ -n "$(WEB_EXT)" ]; then \
	  echo ">> web-ext lint"; $(LOCK) $(WEB_EXT) lint --self-hosted --source-dir $(EXT_DIR) --ignore-files 'test/**' --warnings-as-errors=false; \
	else echo "!! web-ext not installed (npm i -D web-ext in harness/ or npm i -g web-ext); extension lint skipped"; fi

unit: unit-persona unit-harness unit-ext vendor-check ## :persona JUnit (goldens, URL rewrites, userscript headers) + harness + extension node tests + vendor-check

unit-persona:
	$(LOCK) $(GRADLE) :persona:test

unit-harness:
	cd $(HARNESS) && $(NODE) --test test/*.test.mjs

# Extension contract tests incl. the shim size (<= 30 KB) and cold-time (< 5 ms median) budgets; 200 bench
# iterations keep `make unit` short on this box (the default 1000 take ~35 s).
unit-ext:
	cd $(EXT_DIR) && HATCH_SHIM_BENCH_ITER=$${HATCH_SHIM_BENCH_ITER:-200} $(NODE) --test test/*.test.mjs

vendor: ## rebuild harness/vendor/detectors.bundle.js + MANIFEST.json (commit the result)
	cd $(HARNESS) && $(LOCK) npm run vendor-build

vendor-check: ## rebuild the detector bundle in memory and diff against the committed one
	cd $(HARNESS) && $(LOCK) npm run vendor-check

headless: ## headless Firefox 158: all golden personas + negative controls (PERSONAS=id,id to narrow)
	$(NODE) $(HEADLESS)/run.mjs --personas $(PERSONAS) --controls --out $(REPORTS)

headless-noext: ## headless run without the extension (pipeline smoke; expects FAILs)
	$(NODE) $(HEADLESS)/run.mjs --personas $(PERSONAS) --no-ext --out $(REPORTS)

apk: ## release APK (R8) + size budget check -> build/size.json
	tool/build_apk.sh release
	tool/check_apk_size.sh

android-lint: ## Android lint on the release variant (under the lock)
	$(LOCK) $(GRADLE) :app:lintRelease

check: lint unit headless ## serial: lint -> unit -> headless

check-full: check apk android-lint ## check + apk + android-lint

clean-reports:
	rm -rf $(REPORTS)
