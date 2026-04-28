// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

interface IContractUsingMonitor {

    function recvUnorderedMessageFromSDP(string memory senderDomain, bytes32 author, address receiverID, bytes memory message) external;

    function recvMessageFromSDP(string memory senderDomain, bytes32 author, address receiverID, bytes memory message) external;
}