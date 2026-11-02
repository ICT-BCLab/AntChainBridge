// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;

import {AuthMsg} from "xun/AuthMsg.sol";
import "xun/lib/monitor/MonitorLib.sol";
import "xun/lib/sdp/SDPLib.sol";
import "xun/interfaces/IAuthMessage.sol";

/** Offline design prototype only: not included by compile.cjs or business deployments. */
contract OutboundAliasAuthFixture is AuthMsg {
    function authorizeOutboundMonitor(address monitor, uint32 protocolType) external onlyOwner {
        require(monitor != address(0) && protocolRoutes[protocolType] != address(0), "invalid outbound alias");
        SubProtocol storage p = subProtocols[monitor];
        require(!p.exist || p.protocolType == protocolType, "conflicting outbound alias");
        p.exist = true;
        p.protocolType = protocolType;
        // The inbound protocol route continues to point to the canonical SDP.
    }
}

contract FastSourceMonitorFixture {
    address public owner;
    uint256 private sourceConfig;
    mapping(bytes32 => bool) public senderBlacklist;
    mapping(bytes32 => bool) public receiverBlacklist;
    constructor(address am, uint32 control) {
        owner = msg.sender;
        sourceConfig = uint256(uint160(am)) | (uint256(control) << 160);
    }
    function blockSender(bytes32 sender, bool blocked) external {
        require(msg.sender == owner, "owner only");
        senderBlacklist[sender] = blocked;
    }
    function blockReceiver(bytes32 receiver, bool blocked) external {
        require(msg.sender == owner, "owner only");
        receiverBlacklist[receiver] = blocked;
    }
    function sendUnorderedMonitorMessage(string calldata domain, bytes32 receiver, bytes calldata body) external {
        uint256 config = sourceConfig;
        uint32 control = uint32(config >> 160);
        if (control == 2) {
            require(!senderBlacklist[bytes32(uint256(uint160(msg.sender)))] && !receiverBlacklist[receiver], "blocked");
        }
        bytes memory envelope = MonitorLib.encode(MonitorMessage(control, "", body));
        SDPMessage memory sdp = SDPMessage(domain, receiver, envelope, 0xffffffff);
        IAuthMessage(address(uint160(config))).recvFromProtocol(msg.sender, SDPLib.encode(sdp));
    }
}
