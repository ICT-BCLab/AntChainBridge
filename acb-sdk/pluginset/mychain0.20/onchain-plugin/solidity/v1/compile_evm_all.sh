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

SOLCJS_VERSION="$("${SOLCJS}" --version 2>&1)"
if [[ "${SOLCJS_VERSION}" != *"0.8.14"* || "${SOLCJS_VERSION}" != *".mod."* ]]; then
  echo "solcjs must be the Mychain 0.8.14 compiler, got: ${SOLCJS_VERSION}" >&2
  exit 1
fi

SOLC_VERSION="$("${SOLC}" --version 2>&1)"
if [[ "${SOLC_VERSION}" != *"0.8.14"* || "${SOLC_VERSION}" != *"for antfin"* ]]; then
  echo "solc must be the Mychain 0.8.14 binary compiler, got: ${SOLC_VERSION}" >&2
  exit 1
fi

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

"${SOLC}" --bin-runtime --overwrite "${SOL_FILES[@]}" -o .
for runtime_file in AuthMsg.bin-runtime SDPMsg.bin-runtime PtcHub.bin-runtime CommitteePtcVerifier.bin-runtime Monitor.bin-runtime MonitorVerifier.bin-runtime; do
  copy_bin "${runtime_file}"
done

echo "MyChain EVM contract binaries generated under ${CONTRACT_PATH}"
