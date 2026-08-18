pragma solidity ^0.4.22;

interface MonitorMessageInterface {
    function sendMonitorMessage(
        string receiverDomain,
        bytes32 receiverID,
        bytes message
    ) external;
}

contract MonitorSenderContract {
    address monitor_address;

    function setMonitorAddress(address _monitor_address) public {
        monitor_address = _monitor_address;
    }

    function sendMonitored(
        bytes32 receiver,
        string memory domain,
        bytes memory _msg
    ) public {
        MonitorMessageInterface monitor = MonitorMessageInterface(monitor_address);
        monitor.sendMonitorMessage(domain, receiver, _msg);
    }
}
