#!/usr/bin/env bash
# Runs inside the emulator-runner step (emulator already booted).
set -u
mkdir -p ci-out
python3 ci/emulator/drive.py 2>&1 | tee ci-out/drive.log
exit ${PIPESTATUS[0]}
