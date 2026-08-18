// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.8.0;
pragma experimental ABIEncoderV2;

import "./interfaces/ISDPMessage.sol";
import "./interfaces/IMonitorVerifier.sol";
import "./lib/utils/Ownable.sol";
import "./lib/utils/SizeOf.sol";
import "./lib/utils/TypesToBytes.sol";

contract Monitor is Ownable {
    uint32 public constant IMPLEMENTATION_VERSION = 3;
    uint32 public constant MONITOR_CLOSE = 1;
    uint32 public constant MONITOR_OPEN = 2;
    uint32 public constant MONITOR_ROLLBACK = 3;
    uint8 private constant MAJOR_TYPE_ENABLED = 1;
    uint8 private constant MINOR_TYPE_ADD_TO_BLACKLIST = 0;
    uint8 private constant MINOR_TYPE_REMOVE_FROM_BLACKLIST = 1;
    uint8 private constant MINOR_TYPE_MONITOR_CLOSE = 0;
    uint8 private constant MINOR_TYPE_MONITOR_OPEN = 1;

    identity public sdpAddress;
    identity public monitorVerifierAddress;
    uint32 public monitorControl;

    mapping(bytes32 => bool) public senderBlacklist;
    mapping(bytes32 => bool) public receiverBlacklist;

    event MonitorOrderApplied(
        uint32 orderType,
        bytes32 sender,
        bytes32 receiver,
        bool senderBlocked,
        bool receiverBlocked,
        uint32 monitorControl
    );
    event MonitorMessageSent(identity indexed sender, string receiverDomain, identity receiverID, uint32 monitorType);

    constructor() {
        monitorControl = MONITOR_OPEN;
    }

    function getImplementationVersion() external pure returns (uint32) {
        return IMPLEMENTATION_VERSION;
    }

    function setSdpAddress(identity newSdpAddress) external onlyOwner {
        require(newSdpAddress != identity(0), "Monitor: invalid sdp address");
        sdpAddress = newSdpAddress;
    }

    function setMonitorVerifierAddress(identity newMonitorVerifierAddress) external onlyOwner {
        require(newMonitorVerifierAddress != identity(0), "Monitor: invalid verifier address");
        monitorVerifierAddress = newMonitorVerifierAddress;
    }

    function setMonitorControl(uint32 newMonitorControl) external onlyOwner {
        monitorControl = newMonitorControl;
    }

    function getProtocol() external view returns (identity) {
        return sdpAddress;
    }

    function getMonitorVerifier() external view returns (identity) {
        return monitorVerifierAddress;
    }

    function getMonitorControl() external view returns (uint32) {
        return monitorControl;
    }

    function sendMonitorMessage(
        string calldata receiverDomain,
        identity receiverID,
        bytes calldata message
    ) external {
        require(_preMonitoring(receiverID), "Monitor: pre monitoring blocked");
        bytes memory rawMonitorMessage = _encodeMonitorMessage(monitorControl, message);
        ISDPMessage(sdpAddress).sendMessageFromMonitor(receiverDomain, receiverID, msg.sender, rawMonitorMessage);
        emit MonitorMessageSent(msg.sender, receiverDomain, receiverID, monitorControl);
    }

    function sendUnorderedMonitorMessage(
        string calldata receiverDomain,
        identity receiverID,
        bytes calldata message
    ) external {
        require(_preMonitoring(receiverID), "Monitor: pre monitoring blocked");
        bytes memory rawMonitorMessage = _encodeMonitorMessage(monitorControl, message);
        ISDPMessage(sdpAddress).sendUnorderedMessageFromMonitor(receiverDomain, receiverID, msg.sender, rawMonitorMessage);
        emit MonitorMessageSent(msg.sender, receiverDomain, receiverID, monitorControl);
    }

    function _encodeMonitorMessage(uint32 monitorType, bytes memory message) internal pure returns (bytes memory) {
        bytes memory monitorMsg = new bytes(0);
        uint256 monitorMsgSize = SizeOf.sizeOfBytes(monitorMsg);
        uint256 messageSize = SizeOf.sizeOfBytes(message);
        bytes memory rawMessage = new bytes(4 + monitorMsgSize + messageSize);
        uint256 offset = rawMessage.length;

        TypesToBytes.uint32ToBytes(offset, monitorType, rawMessage);
        offset -= SizeOf.sizeOfUint(32);

        TypesToBytes.stringToBytes(offset, monitorMsg, rawMessage);
        offset -= monitorMsgSize;

        TypesToBytes.stringToBytes(offset, message, rawMessage);
        return rawMessage;
    }

    function preMonitoring(identity receiverID) external view returns (bool) {
        return _preMonitoring(receiverID);
    }

    function recvMonitorOrder(
        string calldata committeeId,
        string calldata signAlgo,
        bytes calldata rawProof,
        bytes calldata rawMonitorOrder
    ) external {
        require(monitorVerifierAddress != identity(0), "Monitor: verifier not set");
        require(
            IMonitorVerifier(monitorVerifierAddress).verifyMonitorOrder(committeeId, signAlgo, rawProof, rawMonitorOrder),
            "Monitor: invalid monitor order signature"
        );
        _applyMonitorOrder(rawMonitorOrder);
    }

    function updateSenderBlacklist(bytes32 sender, bool blocked) external onlyOwner {
        senderBlacklist[sender] = blocked;
    }

    function updateReceiverBlacklist(bytes32 receiver, bool blocked) external onlyOwner {
        receiverBlacklist[receiver] = blocked;
    }

    function _preMonitoring(identity receiverID) internal view returns (bool) {
        require(monitorControl != MONITOR_CLOSE, "Monitor: monitor closed");
        if (senderBlacklist[bytes32(msg.sender)]) {
            return false;
        }
        if (receiverBlacklist[bytes32(receiverID)]) {
            return false;
        }
        return true;
    }

    function _applyMonitorOrder(bytes memory rawMonitorOrder) internal {
        (uint32 orderType, bytes32 sender, bytes32 receiver) = _decodeMonitorOrder(rawMonitorOrder);
        bool applied = false;

        uint8 addressChunk = uint8((orderType >> 28) & 0xF);
        if (((addressChunk >> 3) & 0x1) == MAJOR_TYPE_ENABLED) {
            uint8 addressAction = addressChunk & 0x7;
            require(
                addressAction == MINOR_TYPE_ADD_TO_BLACKLIST
                    || addressAction == MINOR_TYPE_REMOVE_FROM_BLACKLIST,
                "Monitor: unsupported blacklist action"
            );
            bool blocked = addressAction == MINOR_TYPE_ADD_TO_BLACKLIST;
            senderBlacklist[sender] = blocked;
            receiverBlacklist[receiver] = blocked;
            applied = true;
        }

        uint8 controlChunk = uint8((orderType >> 24) & 0xF);
        if (((controlChunk >> 3) & 0x1) == MAJOR_TYPE_ENABLED) {
            uint8 controlAction = controlChunk & 0x7;
            if (controlAction == MINOR_TYPE_MONITOR_CLOSE) {
                monitorControl = MONITOR_CLOSE;
            } else if (controlAction == MINOR_TYPE_MONITOR_OPEN) {
                monitorControl = MONITOR_OPEN;
            } else {
                revert("Monitor: unsupported control action");
            }
            applied = true;
        }

        require(applied, "Monitor: unsupported order type");
        emit MonitorOrderApplied(
            orderType,
            sender,
            receiver,
            senderBlacklist[sender],
            receiverBlacklist[receiver],
            monitorControl
        );
    }

    function _decodeMonitorOrder(bytes memory rawMonitorOrder)
        internal
        pure
        returns (uint32 orderType, bytes32 sender, bytes32 receiver)
    {
        uint256 offset = rawMonitorOrder.length;
        offset = _skipVarBytes(rawMonitorOrder, offset);
        offset = _skipVarBytes(rawMonitorOrder, offset);

        require(offset >= 4, "Monitor: missing order type");
        orderType = _readUint32(rawMonitorOrder, offset - 4);
        offset -= 4;

        offset = _skipVarBytes(rawMonitorOrder, offset);
        require(offset >= 32, "Monitor: missing sender");
        offset -= 32;
        sender = _readBytes32(rawMonitorOrder, offset);

        offset = _skipVarBytes(rawMonitorOrder, offset);
        require(offset >= 32, "Monitor: missing receiver");
        offset -= 32;
        receiver = _readBytes32(rawMonitorOrder, offset);
    }

    function _skipVarBytes(bytes memory data, uint256 offset) internal pure returns (uint256) {
        require(offset >= 4, "Monitor: malformed variable field");
        uint32 length = _readUint32(data, offset - 4);
        require(offset >= uint256(length) + 4, "Monitor: truncated variable field");
        return offset - uint256(length) - 4;
    }

    function _readUint32(bytes memory data, uint256 offset) internal pure returns (uint32 value) {
        require(offset + 4 <= data.length, "Monitor: uint32 out of bounds");
        value = (uint32(uint8(data[offset])) << 24)
            | (uint32(uint8(data[offset + 1])) << 16)
            | (uint32(uint8(data[offset + 2])) << 8)
            | uint32(uint8(data[offset + 3]));
    }

    function _readBytes32(bytes memory data, uint256 offset) internal pure returns (bytes32 value) {
        require(offset + 32 <= data.length, "Monitor: bytes32 out of bounds");
        assembly {
            value := mload(add(add(data, 0x20), offset))
        }
    }
}
