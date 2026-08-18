// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.22;

interface IMonitorVerifier {
    function verifyMonitorOrder(
        string committeeId,
        string signAlgo,
        bytes rawProof,
        bytes rawMonitorOrder
    ) external view returns (bool);
}
