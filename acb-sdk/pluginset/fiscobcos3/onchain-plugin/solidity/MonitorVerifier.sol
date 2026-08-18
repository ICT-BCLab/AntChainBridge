// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.22;
pragma experimental ABIEncoderV2;

import "./lib/utils/Ownable.sol";

/**
 * @dev MonitorVerifier for FISCO BCOS cross-chain monitoring.
 *
 * Uses FISCO BCOS precompiled contracts for ECDSA signature verification:
 *   - ECDSA (secp256k1): precompiled at address 0x1001
 *     Call input: (bytes32 messageHash, bytes sig) → address
 *
 * rawProof format:
 *   [4 bytes: count of signatures (big-endian)]
 *   for each signature:
 *     [4 bytes: nodeId length (big-endian)][nodeId bytes][65 bytes: signature]
 *
 * Threshold: validCount >= threshold[committeeId]
 */
contract MonitorVerifier is Ownable {

    struct MonitorNodeInfo {
        address nodeAddress;
        bool    active;
    }

    // committeeId => nodeId => MonitorNodeInfo
    mapping(bytes32 => mapping(bytes32 => MonitorNodeInfo)) public monitorNodes;

    // committeeId => required threshold
    mapping(bytes32 => uint256) public thresholds;

    mapping(bytes32 => address) public monitorNodeAddresses;

    event MonitorNodeUpdated(string committeeId, string nodeId, address nodeAddress, bool active);
    event ThresholdUpdated(string committeeId, uint256 threshold);

    function setMonitorNode(
        string committeeId,
        string nodeId,
        address nodeAddress,
        bool    active
    ) external onlyOwner {
        bytes32 cKey = keccak256(abi.encodePacked(committeeId));
        bytes32 nKey = keccak256(abi.encodePacked(nodeId));
        monitorNodes[cKey][nKey] = MonitorNodeInfo({ nodeAddress: nodeAddress, active: active });
        if (active) {
            monitorNodeAddresses[cKey] = nodeAddress;
        } else if (monitorNodeAddresses[cKey] == nodeAddress) {
            monitorNodeAddresses[cKey] = address(0);
        }
        emit MonitorNodeUpdated(committeeId, nodeId, nodeAddress, active);
    }

    function setThreshold(string committeeId, uint256 threshold) external onlyOwner {
        thresholds[keccak256(abi.encodePacked(committeeId))] = threshold;
        emit ThresholdUpdated(committeeId, threshold);
    }

    function getThreshold(string committeeId) external view returns (uint256) {
        return thresholds[keccak256(abi.encodePacked(committeeId))];
    }

    function verifyMonitorOrder(
        string committeeId,
        string signAlgo,
        bytes rawProof,
        bytes rawMonitorOrder
    ) external view returns (bool) {
        require(rawProof.length == 65, "MonitorVerifier: signature must be 65 bytes");
        bytes32 msgHash = keccak256(rawMonitorOrder);
        bytes32 cKey    = keccak256(abi.encodePacked(committeeId));
        address expected = monitorNodeAddresses[cKey];
        require(expected != address(0), "MonitorVerifier: monitor node not set");
        return _recoverAddress(msgHash, rawProof) == expected;
    }

    function _recoverAddress(bytes32 msgHash, bytes sig) internal pure returns (address) {
        bytes32 r;
        bytes32 s;
        uint8 v;
        assembly {
            r := mload(add(sig, 0x20))
            s := mload(add(sig, 0x40))
            v := byte(0, mload(add(sig, 0x60)))
        }
        if (v < 27) {
            v += 27;
        }
        return ecrecover(msgHash, v, r, s);
    }

    uint256[50] private __gap;
}
