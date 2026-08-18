// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

interface IMonitorVerifier {
    function updateMonitorNodeEndorseInfo(bytes calldata rawEndorseRoot) external;

    function verifyMonitorOrder(
        string calldata committeeId,
        string calldata signAlgo,
        bytes calldata rawProof,
        bytes calldata rawMonitorOrder
    ) external returns (bool);
}
