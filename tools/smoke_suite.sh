#!/usr/bin/env bash
# Load release JARs on a real dedicated Forge runtime without accepting an EULA.
set -euo pipefail
ROOT="$(pwd)"
mkdir -p /tmp/nuovoordine-smoke
cd /tmp/nuovoordine-smoke
curl -fLsS https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.13/forge-1.20.1-47.4.13-installer.jar -o installer.jar
java -jar installer.jar --installServer > install.log 2>&1
mkdir -p mods
cp "$ROOT"/projects/nuovo-ordine-suite/*/build/libs/*.jar mods/
printf 'eula=false\n' > eula.txt
set +e
timeout 120 bash run.sh nogui > smoke.log 2>&1
status=$?
set -e
cat smoke.log
if [ "$status" -eq 124 ]; then echo 'Server smoke timed out'; exit 1; fi
if grep -E 'Failed to create mod instance|ModLoadingException|NoClassDefFoundError|MixinApplyError|InvalidMixinException' smoke.log; then exit 1; fi
grep -qi 'agree to the EULA' smoke.log
# The EULA gate is the intentional stopping point; this is not a gameplay test.
