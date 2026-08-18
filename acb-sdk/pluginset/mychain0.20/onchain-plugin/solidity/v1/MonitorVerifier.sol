// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;
pragma experimental ABIEncoderV2;

import "./interfaces/IMonitorVerifier.sol";
import "./lib/utils/Ownable.sol";
import "./lib/ptc/CommitteeLib.sol";
import "./lib/commons/AcbCommons.sol";
import "./@openzeppelin/contracts/utils/Strings.sol";

contract MonitorVerifier is IMonitorVerifier, Ownable {
    using Strings for string;
    using CommitteeLib for NodePublicKeyEntry;

    mapping(bytes32 => NodeEndorseInfo) private monitorNodeEndorseInfoMap;
    identity public ptcHubAddress;

    event MonitorNodeEndorseInfoUpdated(string committeeId, string nodeId, bytes rawPublicKey);

    modifier onlyPtcHub() {
        require(msg.sender == ptcHubAddress, "MonitorVerifier: caller is not PtcHub");
        _;
    }

    function setPtcHubAddress(identity newPtcHubAddress) external onlyOwner {
        require(newPtcHubAddress != identity(0), "MonitorVerifier: invalid PtcHub address");
        ptcHubAddress = newPtcHubAddress;
    }

    function getPtcHubAddress() external view returns (identity) {
        return ptcHubAddress;
    }

    function updateMonitorNodeEndorseInfo(bytes calldata rawEndorseRoot) external override onlyPtcHub {
        CommitteeEndorseRoot memory endorseRoot = CommitteeLib.decodeCommitteeEndorseRootFrom(rawEndorseRoot);
        require(bytes(endorseRoot.committeeId).length > 0, "MonitorVerifier: empty committee id");

        for (uint256 index = 0; index < endorseRoot.endorsers.length; index++) {
            NodeEndorseInfo memory info = endorseRoot.endorsers[index];
            if (_isMonitorNode(info.nodeId)) {
                monitorNodeEndorseInfoMap[keccak256(abi.encodePacked(endorseRoot.committeeId))] = info;
                emit MonitorNodeEndorseInfoUpdated(
                    endorseRoot.committeeId,
                    info.nodeId,
                    info.publicKey.rawPublicKey
                );
                return;
            }
        }
        revert("MonitorVerifier: monitor node not found");
    }

    function verifyMonitorOrder(
        string calldata committeeId,
        string calldata signAlgo,
        bytes calldata rawProof,
        bytes calldata rawMonitorOrder
    ) external override returns (bool) {
        NodeEndorseInfo storage monitorNode =
            monitorNodeEndorseInfoMap[keccak256(abi.encodePacked(committeeId))];
        require(bytes(monitorNode.nodeId).length > 0, "MonitorVerifier: monitor node not found");
        return AcbCommons.verifySig(
            signAlgo,
            monitorNode.publicKey.getRawPublicKey(),
            rawMonitorOrder,
            rawProof
        );
    }

    function _isMonitorNode(string memory nodeId) internal pure returns (bool) {
        bytes memory prefix = bytes("monitor");
        bytes memory nodeIdBytes = bytes(nodeId);
        if (nodeIdBytes.length < prefix.length) {
            return false;
        }
        for (uint256 index = 0; index < prefix.length; index++) {
            if (nodeIdBytes[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }
}
