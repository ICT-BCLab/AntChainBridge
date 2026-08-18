#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
CONTRACT_PATH="${PLUGIN_DIR}/offchain-plugin/src/main/resources/contract/v1/solidity"

SOLCJS="${SOLCJS:-solcjs}"
SOLC="${SOLC:-solc}"

cd "${SCRIPT_DIR}"
mkdir -p "${CONTRACT_PATH}"
cleanup() {
  rm -f ./*.bin ./*.bin-runtime
}
trap cleanup EXIT

SOL_FILES=(
  AuthMsg.sol
  SDPMsg.sol
  PtcHub.sol
  CommitteePtcVerifier.sol
  Monitor.sol
  MonitorVerifier.sol
  interfaces/*.sol
  lib/am/*.sol
  lib/commons/*.sol
  lib/ptc/*.sol
  lib/sdp/*.sol
  lib/utils/*.sol
  @openzeppelin/contracts/access/*.sol
  @openzeppelin/contracts/interfaces/*.sol
  @openzeppelin/contracts/utils/*.sol
  @openzeppelin/contracts/utils/cryptography/*.sol
  @openzeppelin/contracts/utils/math/*.sol
)

cleanup

"${SOLCJS}" --bin "${SOL_FILES[@]}"

copy_bin() {
  local file="$1"
  if [[ ! -f "${file}" ]]; then
    echo "missing compiler output: ${file}" >&2
    exit 1
  fi
  cp "${file}" "${CONTRACT_PATH}/${file}"
}

copy_bin AuthMsg_sol_AuthMsg.bin
copy_bin SDPMsg_sol_SDPMsg.bin
copy_bin PtcHub_sol_PtcHub.bin
copy_bin CommitteePtcVerifier_sol_CommitteePtcVerifier.bin
copy_bin Monitor_sol_Monitor.bin
copy_bin MonitorVerifier_sol_MonitorVerifier.bin

if command -v "${SOLC}" >/dev/null 2>&1; then
  "${SOLC}" --bin-runtime --overwrite "${SOL_FILES[@]}" -o .
  for runtime_file in AuthMsg.bin-runtime SDPMsg.bin-runtime PtcHub.bin-runtime CommitteePtcVerifier.bin-runtime Monitor.bin-runtime MonitorVerifier.bin-runtime; do
    if [[ -f "${runtime_file}" ]]; then
      cp "${runtime_file}" "${CONTRACT_PATH}/${runtime_file}"
    fi
  done
fi

echo "MyChain EVM contract binaries generated under ${CONTRACT_PATH}"
