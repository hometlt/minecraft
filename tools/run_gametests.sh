#!/bin/bash
# Game tests in two runs (owner 17.10): the light classes, then the heavy ones (paths, balance fights, structures,
# far bases, other worlds), so the heavy ones do not slow the others down. Each run's report is kept:
# build/gametest/junit-light.xml, build/gametest/junit-heavy.xml. Exit status: non-zero if either run failed.
cd "$(dirname "$0")/.." || exit 1
status=0
for part in light heavy; do
	./gradlew runGametest -PstarmineGameTests=$part --console=plain || status=1
	cp build/gametest/junit.xml "build/gametest/junit-$part.xml" 2>/dev/null
done
exit $status
