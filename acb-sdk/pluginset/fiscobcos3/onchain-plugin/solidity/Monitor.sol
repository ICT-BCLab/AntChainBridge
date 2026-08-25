// SPDX-License-Identifier: Apache-2.0
pragma solidity ^0.4.22;
pragma experimental ABIEncoderV2;

import "./interfaces/ISDPMessage.sol";
import "./interfaces/IContractUsingSDP.sol";
import "./interfaces/IMonitorVerifier.sol";
import "./lib/utils/Ownable.sol";
import "./lib/utils/BytesToTypes.sol";
import "./lib/utils/SizeOf.sol";
import "./lib/utils/TypesToBytes.sol";

/**
 * @dev Monitor contract for FISCO BCOS cross-chain monitoring.
 *
 * Business contracts call sendMonitorMessage() instead of calling SDP directly.
 * The Monitor contract performs pre-monitoring checks (blacklist etc.)
 * before routing the message to SDP.
 *
 * Supervisory nodes can issue monitor orders via recvMonitorOrder() to:
 *   - Open/close cross-chain messaging (MONITOR_OPEN / MONITOR_CLOSE)
 *   - Update blacklists (MONITOR_ORDER)
 */
contract Monitor is Ownable {

    uint32 constant IMPLEMENTATION_VERSION = 2;
    uint32 constant MONITOR_CLOSE    = 1;
    uint32 constant MONITOR_OPEN     = 2;
    uint32 constant MONITOR_ROLLBACK = 3;
    uint8 constant MAJOR_TYPE_ENABLED = 1;
    uint8 constant MINOR_TYPE_ADD_TO_BLACKLIST = 0;
    uint8 constant MINOR_TYPE_REMOVE_FROM_BLACKLIST = 1;
    uint8 constant MINOR_TYPE_MONITOR_CLOSE = 0;
    uint8 constant MINOR_TYPE_MONITOR_OPEN = 1;

    address public sdpAddress;
    address public monitorVerifierAddress;

    uint32 public monitorControl;

    // sender address (as bytes32) => blacklisted
    mapping(bytes32 => bool) public senderBlacklist;
    // receiver id (bytes32) => blacklisted
    mapping(bytes32 => bool) public receiverBlacklist;

    event MonitorOrderApplied(uint32 orderType, bytes orderData);
    event MessageSent(address indexed sender, string receiverDomain, bytes32 receiverID);
    event MessageBlocked(address indexed sender, string receiverDomain, bytes32 receiverID, string reason);
    event MessageReceived(string senderDomain, bytes32 indexed sender, address indexed receiver, uint32 monitorType, bool unordered);

    modifier onlySubProtocol() {
        require(msg.sender == sdpAddress, "Monitor: sender is not sdp");
        _;
    }

    constructor() public {
        monitorControl = MONITOR_OPEN;
    }

    function getImplementationVersion() external pure returns (uint32) {
        return IMPLEMENTATION_VERSION;
    }

    function setSdpAddress(address _sdpAddress) external onlyOwner {
        require(_sdpAddress != address(0), "Monitor: invalid sdp address");
        sdpAddress = _sdpAddress;
    }

    function setMonitorVerifierAddress(address _addr) external onlyOwner {
        require(_addr != address(0), "Monitor: invalid verifier address");
        monitorVerifierAddress = _addr;
    }

    function setMonitorControl(uint32 _control) external onlyOwner {
        require(
            _control == MONITOR_CLOSE || _control == MONITOR_OPEN || _control == MONITOR_ROLLBACK,
            "Monitor: invalid monitor control"
        );
        monitorControl = _control;
    }

    /**
     * @dev Called by business contracts to send a monitored cross-chain message.
     * Replaces direct calls to SDP.sendMessage().
     */
    function sendMonitorMessage(
        string receiverDomain,
        bytes32 receiverID,
        bytes message
    ) external {
        if (monitorControl == MONITOR_OPEN) {
            bytes32 senderKey = bytes32(uint256(msg.sender));
            if (senderBlacklist[senderKey]) {
                emit MessageBlocked(msg.sender, receiverDomain, receiverID, "sender blacklisted");
                revert("Monitor: sender is in blacklist");
            }
            if (receiverBlacklist[receiverID]) {
                emit MessageBlocked(msg.sender, receiverDomain, receiverID, "receiver blacklisted");
                revert("Monitor: receiver is in blacklist");
            }
        }

        bytes memory rawMonitorMessage = _encodeMonitorMessage(monitorControl, message);
        emit MessageSent(msg.sender, receiverDomain, receiverID);
        ISDPMessage(sdpAddress).sendMessage(receiverDomain, receiverID, msg.sender, rawMonitorMessage);
    }

    function sendUnorderedMonitorMessage(
        string receiverDomain,
        bytes32 receiverID,
        bytes message
    ) external {
        if (monitorControl == MONITOR_OPEN) {
            bytes32 senderKey = bytes32(uint256(msg.sender));
            require(!senderBlacklist[senderKey], "Monitor: sender is in blacklist");
            require(!receiverBlacklist[receiverID], "Monitor: receiver is in blacklist");
        }

        bytes memory rawMonitorMessage = _encodeMonitorMessage(monitorControl, message);
        emit MessageSent(msg.sender, receiverDomain, receiverID);
        ISDPMessage(sdpAddress).sendUnorderedMessage(receiverDomain, receiverID, msg.sender, rawMonitorMessage);
    }

    function recvMessageFromSDP(
        string senderDomain,
        bytes32 author,
        address receiverID,
        bytes rawMessage
    ) external onlySubProtocol {
        uint32 monitorType;
        bytes memory message;
        (monitorType, message) = _decodeMonitorMessage(rawMessage);
        IContractUsingSDP(receiverID).recvMessage(senderDomain, author, message);
        emit MessageReceived(senderDomain, author, receiverID, monitorType, false);
    }

    function recvUnorderedMessageFromSDP(
        string senderDomain,
        bytes32 author,
        address receiverID,
        bytes rawMessage
    ) external onlySubProtocol {
        uint32 monitorType;
        bytes memory message;
        (monitorType, message) = _decodeMonitorMessage(rawMessage);
        IContractUsingSDP(receiverID).recvUnorderedMessage(senderDomain, author, message);
        emit MessageReceived(senderDomain, author, receiverID, monitorType, true);
    }

    function _encodeMonitorMessage(uint32 monitorType, bytes message) internal pure returns (bytes memory) {
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

    function _decodeMonitorMessage(bytes rawMessage)
        internal
        pure
        returns (uint32 monitorType, bytes memory message)
    {
        uint256 offset = rawMessage.length;
        require(offset >= 68, "Monitor: malformed monitor message");

        monitorType = BytesToTypes.bytesToUint32(offset, rawMessage);
        require(
            monitorType == MONITOR_CLOSE || monitorType == MONITOR_OPEN || monitorType == MONITOR_ROLLBACK,
            "Monitor: invalid monitor type"
        );
        offset -= SizeOf.sizeOfUint(32);

        bytes memory monitorMsg = BytesToTypes.bytesToVarBytes(offset, rawMessage);
        uint256 monitorMsgSize = SizeOf.sizeOfBytes(monitorMsg);
        require(offset >= monitorMsgSize, "Monitor: malformed monitor metadata");
        offset -= monitorMsgSize;

        message = BytesToTypes.bytesToVarBytes(offset, rawMessage);
        require(offset == SizeOf.sizeOfBytes(message), "Monitor: trailing monitor message data");
    }

    /**
     * @dev Called by supervisory system to issue a monitor order.
     * The order is verified by MonitorVerifier before being applied.
     *
     * @param committeeId  the id of the supervising committee
     * @param signAlgo     signature algorithm ("SM2" or "ECDSA")
     * @param rawProof     encoded multi-signature proof
     * @param rawMonitorOrder  encoded monitor order payload
     */
    function recvMonitorOrder(
        string committeeId,
        string signAlgo,
        bytes rawProof,
        bytes rawMonitorOrder
    ) external {
        require(
            IMonitorVerifier(monitorVerifierAddress).verifyMonitorOrder(committeeId, signAlgo, rawProof, rawMonitorOrder),
            "Monitor: invalid monitor order signature"
        );
        _applyMonitorOrder(rawMonitorOrder);
    }

    /**
     * @dev Apply a verified monitor order.
     * Order encoding is the reverse variable-length format produced by MonitorOrderV1.
     */
    function _applyMonitorOrder(bytes rawOrder) internal {
        uint32 orderType;
        bytes32 sender;
        bytes32 receiver;
        (orderType, sender, receiver) = _decodeMonitorOrder(rawOrder);
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

        emit MonitorOrderApplied(orderType, rawOrder);
    }

    function _decodeMonitorOrder(bytes rawOrder)
        internal
        pure
        returns (uint32 orderType, bytes32 sender, bytes32 receiver)
    {
        uint256 offset = rawOrder.length;
        offset = _skipVarBytes(rawOrder, offset);
        offset = _skipVarBytes(rawOrder, offset);

        require(offset >= 4, "Monitor: missing order type");
        orderType = _readUint32(rawOrder, offset - 4);
        offset -= 4;

        offset = _skipVarBytes(rawOrder, offset);
        require(offset >= 32, "Monitor: missing sender");
        offset -= 32;
        sender = _readBytes32(rawOrder, offset);

        offset = _skipVarBytes(rawOrder, offset);
        require(offset >= 32, "Monitor: missing receiver");
        offset -= 32;
        receiver = _readBytes32(rawOrder, offset);
    }

    function _skipVarBytes(bytes data, uint256 offset) internal pure returns (uint256) {
        require(offset >= 4, "Monitor: malformed variable field");
        uint32 length = _readUint32(data, offset - 4);
        require(offset >= uint256(length) + 4, "Monitor: truncated variable field");
        return offset - uint256(length) - 4;
    }

    function _readUint32(bytes data, uint256 offset) internal pure returns (uint32 value) {
        require(offset + 4 <= data.length, "Monitor: uint32 out of bounds");
        value = (uint32(uint8(data[offset])) << 24)
              | (uint32(uint8(data[offset + 1])) << 16)
              | (uint32(uint8(data[offset + 2])) << 8)
              | uint32(uint8(data[offset + 3]));
    }

    function _readBytes32(bytes data, uint256 offset) internal pure returns (bytes32 value) {
        require(offset + 32 <= data.length, "Monitor: bytes32 out of bounds");
        assembly {
            value := mload(add(add(data, 0x20), offset))
        }
    }

    /**
     * @dev Convenience: manually update blacklist (owner only, for testing/admin).
     */
    function updateSenderBlacklist(bytes32 sender, bool blocked) external onlyOwner {
        senderBlacklist[sender] = blocked;
    }

    function updateReceiverBlacklist(bytes32 receiver, bool blocked) external onlyOwner {
        receiverBlacklist[receiver] = blocked;
    }

    uint256[50] private __gap;
}
