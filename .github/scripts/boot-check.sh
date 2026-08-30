#!/usr/bin/env bash
# Boots the server with StaffCore, checks every hook, then actually uses the mod.
#
# Two failures this catches that a compiler cannot:
#
#   1. A mixin that compiles, ships, and silently stops applying. Both of the ones that did
#      this during development were found by a human reading the boot log; the health check
#      already knew, and this makes CI read it.
#   2. A command, query or screen that is wired up wrong. The health check proves the hooks
#      attached, not that anything works — so after it passes, this drives real commands
#      through the console against a real database and fails on an exception from any of
#      them. That is the difference between "it started" and "it runs".
set -uo pipefail

LOG="${1:-boot.log}"
TIMEOUT="${2:-420}"

mkdir -p run/server
echo "eula=true" > run/server/eula.txt
{
  echo "online-mode=false"
  echo "max-players=2"
  echo "server-port=25599"
  echo "sync-chunk-writes=false"
} > run/server/server.properties

echo "Booting the server (timeout ${TIMEOUT}s)..."
timeout "${TIMEOUT}" ./gradlew runServer --console=plain \
  -Pselftest > "${LOG}" 2>&1 &
GRADLE_PID=$!

cleanup() {
  kill "${GRADLE_PID}" 2>/dev/null || true
  wait "${GRADLE_PID}" 2>/dev/null || true
}
trap cleanup EXIT

# Poll rather than waiting for exit: a healthy server never exits on its own.
DEADLINE=$(( SECONDS + TIMEOUT ))
while (( SECONDS < DEADLINE )); do
  if grep -q "StaffCore\] Self test:" "${LOG}" 2>/dev/null; then
    break
  fi
  if ! kill -0 "${GRADLE_PID}" 2>/dev/null; then
    echo "::error::The server exited before the health check ran."
    tail -60 "${LOG}"
    exit 1
  fi
  sleep 3
done

HEALTH=$(grep "StaffCore\] Health check" "${LOG}" | tail -1 || true)
if [[ -z "${HEALTH}" ]]; then
  echo "::error::No health check line — the server never finished starting."
  tail -60 "${LOG}"
  exit 1
fi

echo "${HEALTH}"

# Any mixin that failed to apply is reported by name, whether or not it owns a named feature.
if grep -qE "StaffCore\] .*(did not apply|Missing hook)" "${LOG}"; then
  echo "::error::A StaffCore hook is broken on this Minecraft version."
  grep -E "StaffCore\] .*(did not apply|Missing hook|  •)" "${LOG}"
  exit 1
fi

if [[ "${HEALTH}" != *"hooks present"* ]]; then
  echo "::error::The health check reported broken features."
  grep -E "StaffCore\]" "${LOG}" | tail -20
  exit 1
fi

echo "All hooks present and applied."

# ------------------------------------------------------------------ self test
#
# The health check proves our hooks attached. It cannot prove the code behind them runs: a
# malformed query, a missing column or a command that never registered all start cleanly and
# fail the first time somebody uses one. The mod runs its own checks at boot when asked, and
# this reads the verdict.
#
# It is driven by a system property rather than by typing into the console, because Gradle
# runs the server through its daemon and stdin does not survive that hop.

SELFTEST=$(grep "StaffCore\] Self test:" "${LOG}" | tail -1 || true)
if [[ -z "${SELFTEST}" ]]; then
  echo "::error::The self test never ran. Was -Dstaffcore.selftest=true passed through?"
  tail -40 "${LOG}"
  exit 1
fi

echo "${SELFTEST}"

if grep -q "StaffCore\]  FAIL" "${LOG}"; then
  echo "::error::A StaffCore self test check failed."
  grep -E "StaffCore\]  (FAIL|PASS)" "${LOG}"
  exit 1
fi

grep -E "StaffCore\]  PASS" "${LOG}" || true

# Console output has to survive a console that is not UTF-8. Chat and menus can say what they
# like — those travel as UTF-8 inside Minecraft's own packets — but the log lands in whatever
# code page the host uses, and on Windows that is a legacy one. An em-dash then arrives as
# mojibake and the first thing anybody sees of this mod looks broken.
#
# Checked against the real output rather than the source, because a string assembled from
# pieces is only non-ASCII once it has been assembled.
# Only our own lines: "(staffcore)" is the logger tag in this format, so this does not trip
# on another mod's output or on Fabric's tab-indented mod list.
BAD_ENCODING=$(LC_ALL=C grep -n '(staffcore)' "${LOG}" | LC_ALL=C grep '[^	 -~]' | head -5 || true)
if [[ -n "${BAD_ENCODING}" ]]; then
  echo "::error::StaffCore wrote non-ASCII to the console; it will be mojibake on a legacy code page."
  echo "${BAD_ENCODING}"
  exit 1
fi

echo "Self test passed: the mod starts, and the things behind the hooks actually run."
