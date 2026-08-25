// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.22;

interface IContractUsingMonitor {
    function recvUnorderedMessageFromSDP(
        string senderDomain,
        bytes32 author,
        address receiverID,
        bytes message
    ) external;

    function recvMessageFromSDP(
        string senderDomain,
        bytes32 author,
        address receiverID,
        bytes message
    ) external;
}
