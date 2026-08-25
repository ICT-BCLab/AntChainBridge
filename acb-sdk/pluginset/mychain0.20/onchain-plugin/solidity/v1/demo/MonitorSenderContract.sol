// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

interface IMonitorMessage {
    function sendUnorderedMonitorMessage(
        string calldata receiverDomain,
        identity receiverID,
        bytes calldata message
    ) external;
}

contract MonitorSenderContract {
    identity public monitorAddress;

    event UnorderedMessageSent(string receiverDomain, identity receiver, bytes message);

    function setMonitorContract(identity newMonitorAddress) external {
        require(newMonitorAddress != identity(0), "MonitorSender: invalid monitor");
        monitorAddress = newMonitorAddress;
    }

    function sendUnordered(identity receiver, string calldata receiverDomain, bytes calldata message) external {
        require(monitorAddress != identity(0), "MonitorSender: monitor not set");
        IMonitorMessage(monitorAddress).sendUnorderedMonitorMessage(receiverDomain, receiver, message);
        emit UnorderedMessageSent(receiverDomain, receiver, message);
    }
}
