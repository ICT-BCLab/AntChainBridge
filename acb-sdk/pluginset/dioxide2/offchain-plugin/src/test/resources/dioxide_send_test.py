import json

from dioxide_python_sdk.client.dioxclient import DioxClient
from dioxide_python_sdk.client.account import DioxAccount
from dioxide_python_sdk.client.contract import Scope
from dioxide_python_sdk.utils.gadget import title_info
from dioxide_python_sdk.config.client_config import Config
import sys

# 修改了默认配置为连接到课题一提供的测试链
client = DioxClient()

dapp01_name = "kt3_01"
dapp02_name = "ict002"
app_contract_name = "AppContract"
monitor_contract_name = "Monitor"
sdp_contract_name = "SDPMsg"
am_contract_name = "AuthMsg"

txhash_sendMsg = None
# txhash_sendMsg = "jztdb2a3kvqntaqtkfna66hygw9z1jcynhtdkj79y8gr0r9n810g"


def sendUnorderedMsg():
    title_info("check node connection")
    print(f"Connecting to: {Config.rpc_url}")
    try:
        overview = client.get_overview()
        if overview:
            print(f"Connected to node: {overview.get('VesionName', 'Unknown')}")
            print(f"Block height: {overview.get('HeadHeight', 'Unknown')}")
        else:
            print(f"Error: Cannot connect to Dioxide node at {Config.rpc_url}")
            sys.exit(1)
    except Exception as e:
        print(f"Error: Cannot connect to Dioxide node - {e}")
        sys.exit(1)

    # create another account which will be the owner of app contract in dapp01 and dapp02
    title_info("create an account")
    pk = "X7I0csd+4QfRXD2KMQjoOweXEXJHE2s6T/wiBz5Gil37KvY6ywVu43Q58q+OW7c3jAeef7EKgaAGCbF76nlr2A=="
    account = DioxAccount.from_key(pk)

    # receiveDomain: {dapp02_name}, message:112233
    # receiver:[need to modify]
    title_info(f"test sending msg from dapp01({dapp01_name}):AppContract to dapp02({dapp02_name}):AppContract")
    txhash_sendMsg = None
    txhash_sendMsg = client.send_transaction(account, f"{dapp01_name}.{app_contract_name}.sendUnorderedMessage", 
                                {"receiverDomain": [101, 116, 104, 48, 49],
                                    "receiver": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 109, 178, 12, 83, 11, 63, 150, 205, 94, 246, 77, 162, 177, 185, 49, 203, 143, 38, 64, 9],
                                    "message": [52, 52, 53, 53, 54, 54]}, is_sync=False)
    if not txhash_sendMsg:
        print("✗ sendUnorderedMessage failed")
        sys.exit(1)
    print(f"[app sendUnorderedMessage]txhash:{txhash_sendMsg}")


def sendOrderedMsg1to2():
    title_info("check node connection")
    print(f"Connecting to: {Config.rpc_url}")
    try:
        overview = client.get_overview()
        if overview:
            print(f"Connected to node: {overview.get('VesionName', 'Unknown')}")
            print(f"Block height: {overview.get('HeadHeight', 'Unknown')}")
        else:
            print(f"Error: Cannot connect to Dioxide node at {Config.rpc_url}")
            sys.exit(1)
    except Exception as e:
        print(f"Error: Cannot connect to Dioxide node - {e}")
        sys.exit(1)

    # create another account which will be the owner of app contract in dapp01 and dapp02
    title_info("create an account")
    pk = "X7I0csd+4QfRXD2KMQjoOweXEXJHE2s6T/wiBz5Gil37KvY6ywVu43Q58q+OW7c3jAeef7EKgaAGCbF76nlr2A=="
    account = DioxAccount.from_key(pk)

    # receiveDomain: {dapp02_name}, message:445566
    # receiver:[need to modify]
    title_info(f"test sending msg from dapp01({dapp01_name}):AppContract to dapp02({dapp02_name}):AppContract")
    txhash_sendMsg = None
    txhash_sendMsg = client.send_transaction(account, f"{dapp01_name}.{app_contract_name}.sendMessage", 
                                {"receiverDomain": [101, 116, 104, 48, 49],
                                    "receiver": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 113, 80, 167, 143, 206, 77, 250, 68, 69, 151, 145, 59, 233, 105, 247, 253, 86, 203, 207, 65],
                                    "message": [52, 52, 53, 53, 54, 54]}, is_sync=False)
    if not txhash_sendMsg:
        print("✗ sendOrderedMessage failed")
        sys.exit(1)
    print(f"[app sendOrderedMessage]txhash:{txhash_sendMsg}")


def sendOrderedMsg2to1():
    title_info("check node connection")
    print(f"Connecting to: {Config.rpc_url}")
    try:
        overview = client.get_overview()
        if overview:
            print(f"Connected to node: {overview.get('VesionName', 'Unknown')}")
            print(f"Block height: {overview.get('HeadHeight', 'Unknown')}")
        else:
            print(f"Error: Cannot connect to Dioxide node at {Config.rpc_url}")
            sys.exit(1)
    except Exception as e:
        print(f"Error: Cannot connect to Dioxide node - {e}")
        sys.exit(1)

    # create another account which will be the owner of app contract in dapp01 and dapp02
    title_info("create an account")
    pk = "X7I0csd+4QfRXD2KMQjoOweXEXJHE2s6T/wiBz5Gil37KvY6ywVu43Q58q+OW7c3jAeef7EKgaAGCbF76nlr2A=="
    account = DioxAccount.from_key(pk)

    # receiveDomain: {dapp01_name}, message:778899
    # receiver:[need to modify]
    title_info(f"test sending msg from dapp02({dapp02_name}):AppContract to dapp01({dapp01_name}):AppContract")
    txhash_sendMsg = None
    txhash_sendMsg = client.send_transaction(account, f"{dapp02_name}.{app_contract_name}.sendMessage", 
                                {"receiverDomain": [100, 105, 111, 120, 48, 49],
                                    "receiver": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 83, 0, 224, 0, 1],
                                    "message": [55, 55, 56, 56, 57, 57]}, is_sync=False)
    if not txhash_sendMsg:
        print("✗ sendOrderedMessage failed")
        sys.exit(1)
    print(f"[app sendOrderedMessage]txhash:{txhash_sendMsg}")


def checkApp01Status():
    print("app01 status")
    print(json.dumps(client.get_contract_state(dapp01_name, app_contract_name, Scope.Global, None).to_dict(), indent=4, ensure_ascii=False))


def checkApp02Status():
    print("app02 status")
    print(json.dumps(client.get_contract_state(dapp02_name, app_contract_name, Scope.Global, None).to_dict(), indent=4, ensure_ascii=False))


def getTxhashInfo():
    print(f"txhash:hg3tnyvx5jn2jhjtxxm0e0xdj29vn5s2bq6t2jp9121zf4yx9xhg")
    print(json.dumps(client.get_transaction("hg3tnyvx5jn2jhjtxxm0e0xdj29vn5s2bq6t2jp9121zf4yx9xhg").to_dict(), indent=4, ensure_ascii=False))


# Event: SendAuthMessage
def findEventInBlock():
    block_height = client.get_transaction("f9k67znwn71ny0hx0g86ay88azh1vf15jsdkfmttv89f3cwn2sr0").Height
    for i in range(block_height-1, block_height+4):
        print(f"[block height: {i}]")
        tx_block = client.get_transaction_block_by_height(65535, i)

        dis_relay_count = tx_block["DispatchedRelayTxnCount"]
        if dis_relay_count != 0:
            dis_relay = tx_block["Transactions"]["DispatchedRelays"]
            for j in dis_relay:
                print(f"[dis_relay: {j}]")
                print(json.dumps(client.get_transaction(j).to_dict(), indent=4, ensure_ascii=False))
        out_relay_count = tx_block["OutboundRelayTxnCount"]
        if out_relay_count != 0:
            out_relay = tx_block["Transactions"]["OutboundRelays"]
            for j in out_relay:
                print(f"[out_relay: {j}]")
                print(json.dumps(client.get_transaction(j).to_dict(), indent=4, ensure_ascii=False))


def to_int_array_from_bytes(byte_arr) -> list[int]:
    int_arr = []
    for b in byte_arr:
        int_arr.append(b & 0xFF)
    return int_arr


def getEthAppIntArray():
    eth_app_64_hex_bytes = "0000000000000000000000006db20C530b3F96CD5ef64Da2b1b931Cb8f264009"
    print("eth_app_int_array: ", to_int_array_from_bytes(bytes.fromhex(eth_app_64_hex_bytes)))


def getDioxideAppAddress():
    app_contract_info = client.get_contract_info(dapp01_name, app_contract_name)
    app_cid = app_contract_info.get('ContractVersionID')
    print(f"app01_cid:{app_cid}")
    print(f"app_cid_32bytes: ", app_cid.to_bytes(32, byteorder="big").hex())
    print("app_cid_int_array: ", to_int_array_from_bytes(app_cid.to_bytes(32, byteorder="big")))

# [说明] 使用每个命令时注意对其中的参数进行更改
# 使用命令1 2 3进行消息发送测试; 使用命令4 5查看应用合约状态;
# 使用命令6查看交易信息; 使用命令7在区块中查找事件(事件只会是DispatchedRelay或者OutboundRelay); 
# 使用命令8获取Ethereum合约地址的整数数组形式，用于填充到命令1 2 3中的receiver
# 使用命令9获取Dioxide合约地址的32字节形式和整数数组形式
COMMANDS = {
    "1": ("sendUnorderedMsg", sendUnorderedMsg),
    "2": ("sendOrderedMsg1to2", sendOrderedMsg1to2),
    "3": ("sendOrderedMsg2to1", sendOrderedMsg2to1),
    "4": ("checkApp01Status", checkApp01Status),
    "5": ("checkApp02Status", checkApp02Status),
    "6": ("getTxhashInfo", getTxhashInfo),
    "7": ("findEventInBlock", findEventInBlock),
    "8": ("getEthAppIntArray", getEthAppIntArray),
    "9": ("getDioxideAppAddress", getDioxideAppAddress),
}

# [使用方式] python test2-AppContractPassingMsg.py 1
def print_usage():
    print("Usage:")
    for k, (name, _) in COMMANDS.items():
        print(f"  {k}: {name}")
    print(f"\nExample:\n  python {sys.argv[0]} 1")

def main():
    if len(sys.argv) < 2:
        print_usage()
        sys.exit(1)

    choice = sys.argv[1]
    entry = COMMANDS.get(choice)

    if entry is None:
        print(f"Unknown choice: {choice}\n")
        print_usage()
        sys.exit(1)

    name, func = entry
    print(f"[RUN] {name}")
    func()

if __name__ == "__main__":
    main()