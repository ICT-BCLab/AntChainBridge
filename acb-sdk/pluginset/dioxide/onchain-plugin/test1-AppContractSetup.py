import json

from dioxide_python_sdk.client.dioxclient import DioxClient
from dioxide_python_sdk.client.account import DioxAccount
from dioxide_python_sdk.client.contract import Scope
from dioxide_python_sdk.utils.gadget import title_info
from dioxide_python_sdk.config.client_config import Config
import sys
import os

client = DioxClient()

dapp01_name = "ict001"
dapp02_name = "ict002"
app_contract_name = "AppContract"
sdp_contract_name = "SDPMsg"
am_contract_name = "AuthMsg"


def to_int_array_from_bytes(byte_arr) -> list[int]:
    int_arr = []
    for b in byte_arr:
        int_arr.append(b & 0xFF)
    return int_arr


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


# get the account which creates dapp01 and dapp02 to deploy app on them
pk1 = "WTKi+W99TEEt153Zt8isUznwXqYkA0aVWEbd7edk6AvivGov5hBLJLQbS2hk8bnC3FM8Et6+Axaw1uukce+ZEQ=="
account_of_dapp = DioxAccount.from_key(pk1)

# create another account which will be the owner of app contract in dapp01 and dapp02
title_info("create an account")
pk = "X7I0csd+4QfRXD2KMQjoOweXEXJHE2s6T/wiBz5Gil37KvY6ywVu43Q58q+OW7c3jAeef7EKgaAGCbF76nlr2A=="
account = DioxAccount.from_key(pk)

title_info("mint some tokens")
result = client.mint_dio(account, 10**18)
if result:
    print(result)
else:
    print("Failed to mint tokens")


# deploy app contract 1 in dapp01
title_info("deploy app contract 1 in dapp01")
contracts = {
    os.path.join("AppContract.gcl"): {"_owner": account.address}
}

try:
    deploy_tx_hash = client.deploy_contracts(
        dapp_name=dapp01_name,
        delegator=account_of_dapp,
        contracts=contracts,
        compile_time=20
    )
    if deploy_tx_hash:
        print(f"✓ Deploy transaction: {deploy_tx_hash}")
    else:
        print("Deploy failed")
        sys.exit(1)
except Exception as e:
    print(f"Deploy error: {e}")
    sys.exit(1)

title_info("verify deployment")
app01_contract_info = client.get_contract_info(dapp01_name, app_contract_name)

if not app01_contract_info:
    print("Contract deployment verification failed")
    sys.exit(1)
print(f"[App in dapp01({dapp01_name})] contract info")
print(json.dumps(app01_contract_info, indent=4, ensure_ascii=False))


# setup app01 contract
title_info("setup app01 contract")
sdp01_contract_info = client.get_contract_info(dapp01_name, sdp_contract_name)
sdp01_cid = sdp01_contract_info.get('ContractVersionID')
sdp01_address = f"0x{sdp01_cid:016X}:contract"
tx1 = client.send_transaction(account, f"{dapp01_name}.{app_contract_name}.setProtocol", {"_protocolContractId": sdp01_cid, "_protocolAddress": sdp01_address}, is_sync=True)
if not tx1:
    print("✗ [app01]setProtocol failed")
    sys.exit(1)
print("app01 status")
print(json.dumps(client.get_contract_state(dapp01_name, app_contract_name, Scope.Global, None).to_dict(), indent=4, ensure_ascii=False))


# [说明] 注释掉的仅为用dioxide-dioxide之间通信时, 需要部署的第二套应用合约; 在dioxide-ethereum2之间通信时, 只需要部署一套应用合约即可
# # deploy app contract 2 in dapp02
# title_info("deploy app contract 2 in dapp02")
# contracts = {
#     os.path.join("AppContract.gcl"): {"_owner": account.address}
# }
# try:
#     deploy_tx_hash = client.deploy_contracts(
#         dapp_name=dapp02_name,
#         delegator=account_of_dapp,
#         contracts=contracts,
#         compile_time=20
#     )
#     if deploy_tx_hash:
#         print(f"✓ Deploy transaction: {deploy_tx_hash}")
#     else:
#         print("Deploy failed")
#         sys.exit(1)
# except Exception as e:
#     print(f"Deploy error: {e}")
#     sys.exit(1)

# title_info("verify deployment")
# app02_contract_info = client.get_contract_info(dapp02_name, app_contract_name)

# if not app02_contract_info:
#     print("Contract deployment verification failed")
#     sys.exit(1)
# print(f"[App in dapp02({dapp02_name})] contract info")
# print(json.dumps(app02_contract_info, indent=4, ensure_ascii=False))


# # setup app02 contract
# title_info("setup app02 contract")
# sdp02_contract_info = client.get_contract_info(dapp02_name, sdp_contract_name)
# sdp02_cid = sdp02_contract_info.get('ContractVersionID')
# sdp02_address = f"0x{sdp02_cid:016X}:contract"
# tx2 = client.send_transaction(account, f"{dapp02_name}.{app_contract_name}.setProtocol", {"_protocolContractId": sdp02_cid, "_protocolAddress": sdp02_address}, is_sync=True)
# if not tx2:
#     print("✗ [app01]setProtocol failed")
#     sys.exit(1)
# print("app02 status")
# print(json.dumps(client.get_contract_state(dapp02_name, app_contract_name, Scope.Global, None).to_dict(), indent=4, ensure_ascii=False))


app01_contract_info = client.get_contract_info(dapp01_name, app_contract_name)
app01_cid = app01_contract_info.get('ContractVersionID')
print(f"app01_cid:{app01_cid}")
print(f"app01_cid_32bytes: ", app01_cid.to_bytes(32, byteorder="big").hex())
print("app01_cid_int_array: ", to_int_array_from_bytes(app01_cid.to_bytes(32, byteorder="big")))
# app02_contract_info = client.get_contract_info(dapp02_name, app_contract_name)
# app02_cid = app02_contract_info.get('ContractVersionID')
# print(f"app02_cid:{app02_cid}")
# print(f"app02_cid_32bytes: ", app02_cid.to_bytes(32, byteorder="big").hex())
# print("app02_cid_int_array: ", to_int_array_from_bytes(app02_cid.to_bytes(32, byteorder="big")))