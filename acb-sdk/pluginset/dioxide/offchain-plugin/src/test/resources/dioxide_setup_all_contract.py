# -*- coding:utf-8 -*-
import json
from re import S
import time

from dioxide_python_sdk.client.dioxclient import DioxClient
from dioxide_python_sdk.client.account import DioxAccount
from dioxide_python_sdk.client.contract import Scope
from dioxide_python_sdk.utils.gadget import title_info
import sys
import os


BASE_DAPP = "kt3_01"
CROSS_TRANSFER_DAPP = "cc_02"
TRANSFER_CONTRACT_NAME = "CrossTransfer"
APP_CONTRACT_NAME = "AppContract"
MONITOR_CONTRACT_NAME = "Monitor"
SDP_CONTRACT_NAME = "SDPMsg"
AM_CONTRACT_NAME = "AuthMsg"


# DEFAULT_RPC_URL = "http://127.0.0.1:62222/api"
# DEFAULT_WS_RPC = "ws://127.0.0.1:62222/api"

DEFAULT_RPC_URL = "http://139.196.213.90:62222/api"
DEFAULT_WS_RPC = "ws://139.196.213.90:62222/api"
DEFAULT_COMPILE_TIME = 20
DEPLOYER_PK = "WTKi+W99TEEt153Zt8isUznwXqYkA0aVWEbd7edk6AvivGov5hBLJLQbS2hk8bnC3FM8Et6+Axaw1uukce+ZEQ=="
INVOKER_PK = "X7I0csd+4QfRXD2KMQjoOweXEXJHE2s6T/wiBz5Gil37KvY6ywVu43Q58q+OW7c3jAeef7EKgaAGCbF76nlr2A=="


class Deployer:
    def __init__(self):
        self.base_dapp = BASE_DAPP
        self.cross_transfer_dapp = CROSS_TRANSFER_DAPP
        self.transfer_contract_name = TRANSFER_CONTRACT_NAME
        self.app_contract_name = APP_CONTRACT_NAME
        self.monitor_contract_name = MONITOR_CONTRACT_NAME
        self.sdp_contract_name = SDP_CONTRACT_NAME
        self.am_contract_name = AM_CONTRACT_NAME
        self.deployer = DioxAccount.from_key(DEPLOYER_PK)
        self.invoker = DioxAccount.from_key(INVOKER_PK)
        self.client = None
        self.rpc_url = DEFAULT_RPC_URL
        self.ws_rpc = DEFAULT_WS_RPC
        self.default_compile_time = DEFAULT_COMPILE_TIME
        self.contracts_dir = os.path.abspath("./onchain-plugin")

    def _abort(self, message, error, context=None):
        if context:
            print(f"{message}: {error} | {context}")
        else:
            print(f"{message}: {error}")
        sys.exit(1)

    def _validate_config(self):
        if not self.base_dapp:
            self._abort("Invalid config", "base_dapp")
        if not self.rpc_url or not self.ws_rpc:
            self._abort("Invalid config", "rpc_url/ws_rpc")
        if self.deployer is None or self.invoker is None:
            self._abort("Invalid config", "deployer/invoker")

    def _ensure_client(self):
        if self.client is None:
            self._abort("Client not initialized", "call initialize() first")

    def _validate_contract_files(self, contracts):
        if not contracts:
            self._abort("Contracts not provided", "empty contracts")
        missing = [path for path in contracts.keys() if not os.path.exists(path)]
        if missing:
            self._abort("Contract file missing", ", ".join(missing))

    def _resolve_contract_name(self, path):
        base = os.path.basename(path)
        return os.path.splitext(base)[0]

    def _verify_deployments(self, client, dapp_name, contracts):
        deployed = []
        time.sleep(5)
        for path in contracts.keys():
            contract_name = self._resolve_contract_name(path)
            info = client.get_contract_info(dapp_name, contract_name)
            if not info or info.get("ContractVersionID") is None:
                self._abort("Contract deploy verification failed", contract_name, f"dapp={dapp_name}")
            deployed.append(contract_name)
        print(f"✓ Deploy verified in {dapp_name}: {', '.join(deployed)}")

    def _verify_state(self, dapp_name, contract_name):
        state = self.client.get_contract_state(dapp_name, contract_name, Scope.Global, None)
        if state is None:
            self._abort("State verification failed", contract_name, f"dapp={dapp_name}")
        print(f"✓ State verified in {dapp_name}: {contract_name}")

    def initialize(self):
        try:
            self._validate_config()
            client = DioxClient(self.rpc_url, self.ws_rpc)
            title_info("check node connection")
            overview = client.get_overview()
            if overview:
                print(f"Connected to node, Block height: {overview.get('HeadHeight', 'Unknown')}")
            else:
                self._abort("Error: Cannot connect to Dioxide node", "empty overview")
            self.client = client
            return client
        except Exception as e:
            self._abort("Error: Cannot connect to Dioxide node", e, f"rpc={self.rpc_url}")

    def mint_tokens(self):
        try:
            title_info("mint some tokens")
            self._ensure_client()
            self.client.mint_dio(self.deployer, 10**18)
        except Exception as e:
            self._abort("Mint failed", e)

    def create_dapp(self, dapp_name):
        try:
            title_info("create dapp")
            self._ensure_client()
            print(f"Creating dapp: {dapp_name}")
            result, ok = self.client.create_dapp(self.deployer, dapp_name, 10**12)
            if ok:
                print(result)
            else:
                self._abort("Failed to create dapp", dapp_name)
        except Exception as e:
            self._abort("Create dapp failed", e, f"dapp={dapp_name}")

    def set_dapp_profile(self, dapp_name):
        try:
            title_info("set dapp profile")
            self._ensure_client()
            deployed_txn = self.client.compose_transaction(
                sender=f"{dapp_name}:dapp",
                function=f"core.profile.set",
                is_delegatee=True,
                args={
                    "Metadata": "{\"IconUrl\":\"https://www.thisiscolossal.com/wp-content/uploads/2018/04/agif2opt.gif\"}",
                },
            )
            tx_hash = self.client.send_raw_transaction(self.deployer.sign_diox_transaction(deployed_txn), True)
            if tx_hash:
                print(f"✓ Set dapp profile transaction: {tx_hash}")
            else:
                print("Set dapp profile failed")
                sys.exit(1)
        except Exception as e:
            self._abort("Set dapp profile failed", e, f"dapp={dapp_name}")


    def deploy_contracts_to_dapp(self, client, deployer, dapp_name, contracts, compile_time=None):
        try:
            if not dapp_name:
                self._abort("Invalid dapp name", dapp_name)
            self._validate_contract_files(contracts)
            if compile_time is None:
                compile_time = self.default_compile_time
            deploy_tx_hash = client.deploy_contracts(
                dapp_name=dapp_name,
                delegator=deployer,
                contracts=contracts,
                compile_time=compile_time
            )
            if deploy_tx_hash:
                print(f"✓ Deploy transaction: {deploy_tx_hash}")
                self._verify_deployments(client, dapp_name, contracts)
            else:
                print("Deploy failed")
                sys.exit(1)
        except Exception as e:
            self._abort("Deploy error", e, f"dapp={dapp_name}, contracts={contracts.keys()}")

    def deploy_base_contracts(self):
        try:
            title_info("deploy contracts")
            self._ensure_client()

            contracts = {
                os.path.join(self.contracts_dir, "interfaces", "IAuthMessage.gcl"): None,
                os.path.join(self.contracts_dir, "interfaces", "IContractUsingMonitor.gcl"): None,
                os.path.join(self.contracts_dir, "interfaces", "IContractUsingSDP.gcl"): None,
                os.path.join(self.contracts_dir, "interfaces", "IMonitor.gcl"): None,
                os.path.join(self.contracts_dir, "interfaces", "ISDPMessage.gcl"): None,
                os.path.join(self.contracts_dir, "interfaces", "ISubProtocol.gcl"): None,
                os.path.join(self.contracts_dir, "lib", "am", "AMLib.gcl"): None,
                os.path.join(self.contracts_dir, "lib", "monitor", "MonitorLib.gcl"): None,
                os.path.join(self.contracts_dir, "lib", "sdp", "SDPLib.gcl"): None,
                os.path.join(self.contracts_dir, "lib", "utils", "BytesToTypes.gcl"): None,
                os.path.join(self.contracts_dir, "lib", "utils", "SizeOf.gcl"): None,
                os.path.join(self.contracts_dir, "lib", "utils", "TLVUtils.gcl"): None,
                os.path.join(self.contracts_dir, "lib", "utils", "TypesToBytes.gcl"): None,
                os.path.join(self.contracts_dir, "lib", "utils", "Utils.gcl"): None,
                os.path.join(self.contracts_dir, "AuthMsg.gcl"): {"_owner": self.deployer.address, "_relayer": self.deployer.address},
                os.path.join(self.contracts_dir, "SDPMsg.gcl"): {"_owner": self.deployer.address},
                os.path.join(self.contracts_dir, "Monitor.gcl"): {"_owner": self.deployer.address}
            }

            self.deploy_contracts_to_dapp(self.client, self.deployer, self.base_dapp, contracts)
            self._ensure_client()
            auth_msg_contract_info = self.client.get_contract_info(self.base_dapp, self.am_contract_name)
            sdp_msg_contract_info = self.client.get_contract_info(self.base_dapp, self.sdp_contract_name)
            monitor_msg_contract_info = self.client.get_contract_info(self.base_dapp, self.monitor_contract_name)

            am_cid = auth_msg_contract_info.get("ContractVersionID")
            am_address = f"0x{am_cid:016X}:contract"
            sdp_cid = sdp_msg_contract_info.get("ContractVersionID")
            sdp_address = f"0x{sdp_cid:016X}:contract"
            monitor_cid = monitor_msg_contract_info.get("ContractVersionID")
            monitor_address = f"0x{monitor_cid:016X}:contract"

            title_info("[Monitor]set sdp to monitor")
            self.client.send_transaction(self.deployer, f"{self.base_dapp}.{self.monitor_contract_name}.setProtocol", {"_sdpContractId": sdp_cid, "_sdpAddress": sdp_address}, is_sync=False)
            print("[Monitor]set sdp to monitor: " + sdp_address)

            title_info("[SDP]set am to sdp")
            self.client.send_transaction(self.deployer, f"{self.base_dapp}.{self.sdp_contract_name}.setAmContract", {"_amContractId": am_cid, "_amAddress": am_address}, is_sync=False)
            print("[SDP]set am to sdp: " + am_address)

            title_info("[SDP]set monitor to sdp")
            self.client.send_transaction(self.deployer, f"{self.base_dapp}.{self.sdp_contract_name}.setMonitorContract", {"_monitorContractId": monitor_cid, "_monitorAddress": monitor_address}, is_sync=False)
            print("[SDP]set monitor to sdp: " + monitor_address)

            # relayer会在注册的时候重新设置的
            title_info("[SDP]set domain to sdp")
            self.client.send_transaction(self.deployer, f"{self.base_dapp}.{self.sdp_contract_name}.setLocalDomain", {"domain": [100, 105, 111, 120, 48, 49]}, is_sync=False)
            print("[SDP]set domain to sdp: [100,105,111,120,48,49]")

            title_info("[AM]set sdp to am")
            self.client.send_transaction(self.deployer, f"{self.base_dapp}.{self.am_contract_name}.setProtocol", {"protocolID": sdp_cid, "protocolAddress": sdp_address, "protocolType": 0}, is_sync=False)
            print("[AM]set sdp to am: " + sdp_address)
        except Exception as e:
            self._abort("Deploy base contracts failed", e, f"dapp={self.base_dapp}")



    def deploy_app_contract(self):
        try:
            title_info(f"deploy AppContract in {self.base_dapp}")
            self._ensure_client()
            contracts = {
                os.path.join(self.contracts_dir, "AppContract.gcl"): {"_owner": self.deployer.address}
            }
            self.deploy_contracts_to_dapp(self.client, self.deployer, self.base_dapp, contracts, compile_time=10)
            title_info("setup AppContract")
            self._ensure_client()
            monitor_msg_contract_info = self.client.get_contract_info(self.base_dapp, self.monitor_contract_name)
            monitor_cid = monitor_msg_contract_info.get("ContractVersionID")
            monitor_address = f"0x{monitor_cid:016X}:contract"
            tx = self.client.send_transaction(self.deployer, f"{self.base_dapp}.{self.app_contract_name}.setMonitor", {"_monitorContractId": monitor_cid, "_monitorAddress": monitor_address}, is_sync=True)
            if not tx:
                self._abort("setProtocol failed", self.app_contract_name)
            print(f"{self.base_dapp} status")
            print(json.dumps(self.client.get_contract_state(self.base_dapp, self.app_contract_name, Scope.Global, None).to_dict(), indent=4, ensure_ascii=False))
            self._verify_state(self.base_dapp, self.app_contract_name)
        except Exception as e:
            self._abort("Deploy AppContract failed", e, f"dapp={self.base_dapp}")

    def deploy_cross_transfer_contract(self):
        try:
            title_info(f"deploy CrossTransfer contract in {self.cross_transfer_dapp}")
            self._ensure_client()
            contracts = {
                os.path.join(self.contracts_dir, "CrossTransfer.gcl"): {"_owner": self.deployer.address}
            }
            self.deploy_contracts_to_dapp(self.client, self.deployer, self.cross_transfer_dapp, contracts, compile_time=10)
            title_info("setup CrossTransfer contract")
            self._ensure_client()
            sdp_contract_info = self.client.get_contract_info(self.base_dapp, self.sdp_contract_name)
            sdp_cid = sdp_contract_info.get("ContractVersionID")
            sdp_address = f"0x{sdp_cid:016X}:contract"
            tx = self.client.send_transaction(self.deployer, f"{self.cross_transfer_dapp}.{self.transfer_contract_name}.setProtocol", {"_protocolContractId": sdp_cid, "_protocolAddress": sdp_address}, is_sync=True)
            if not tx:
                self._abort("setProtocol failed", self.transfer_contract_name)
            print(f"{self.cross_transfer_dapp} status")
            print(json.dumps(self.client.get_contract_state(self.cross_transfer_dapp, self.transfer_contract_name, Scope.Global, None).to_dict(), indent=4, ensure_ascii=False))
            self._verify_state(self.cross_transfer_dapp, self.transfer_contract_name)

            transfer_contract_contract_info = self.client.get_contract_info(self.cross_transfer_dapp, self.transfer_contract_name)
            transfer_contract_cid = transfer_contract_contract_info.get('ContractVersionID')
            print(f"transfer_contract_cid:{transfer_contract_cid}")
            print(f"transfer_contract_cid_32bytes: ", transfer_contract_cid.to_bytes(32, byteorder="big").hex())
            print("transfer_contract_cid_int_array: ", to_int_array_from_bytes(transfer_contract_cid.to_bytes(32, byteorder="big")))

        except Exception as e:
            self._abort("Deploy CrossTransfer failed", e, f"dapp={self.base_dapp}")

    def set_relayer_in_am(self):
        try:
            self._ensure_client()
            auth_msg_contract_info = self.client.get_contract_info(self.base_dapp, self.am_contract_name)
            title_info("reset relaer addr in am")
            am_cid = auth_msg_contract_info.get("ContractVersionID")
            am_address = f"0x{am_cid:016X}:contract"
            self.client.send_transaction(self.deployer, f"{self.base_dapp}.{self.am_contract_name}.setRelayer", {"_relayer": self.deployer.address}, is_sync=False)
            print("[AM]Reset relayer address: " + self.deployer.address)
        except Exception as e:
            self._abort("[AM]Reset relayer address failed", e, f"dapp={self.base_dapp}")

    def get_am_sdp_cid(self):
        self._ensure_client()
        auth_msg_contract_info = self.client.get_contract_info(self.base_dapp, self.am_contract_name)
        sdp_msg_contract_info = self.client.get_contract_info(self.base_dapp, self.sdp_contract_name)
        am_cid = auth_msg_contract_info.get("ContractVersionID")
        am_address = f"0x{am_cid:016X}:contract"
        sdp_cid = sdp_msg_contract_info.get("ContractVersionID")
        sdp_address = f"0x{sdp_cid:016X}:contract"
        print("am cid: " + str(am_cid))
        print("am addr: " + am_address)
        print("sdp cid: " + str(sdp_cid))
        print("sdp addr: " + sdp_address)


    def faucet(self):
        try:
            title_info("CrossTransfer contract: faucet")
            self._ensure_client()
            tx = self.client.send_transaction(self.deployer, f"{self.cross_transfer_dapp}.{self.transfer_contract_name}.faucet", {}, is_sync=True)
            if not tx:
                self._abort("faucet failed", self.transfer_contract_name)
            print(f"{self.cross_transfer_dapp}.{self.transfer_contract_name} status")
            print(json.dumps(self.client.get_contract_state(self.cross_transfer_dapp, self.transfer_contract_name, Scope.Global, None).to_dict(), indent=4, ensure_ascii=False))
        except Exception as e:
            self._abort("CrossTransfer contract: faucet failed", e, f"dapp={self.cross_transfer_dapp}")


    def transfer_to_eth(self):
        try:
            title_info(f"test transfer to eth")
            txhash_sendmsg = self.client.send_transaction(self.deployer, f"{self.cross_transfer_dapp}.{self.transfer_contract_name}.crossTransfer",
                                                     {"receiverDomain": [101, 116, 104, 48, 49], # eth01 ascii
                                                      "receiver": [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 10, 1, 239, 5, 30, 254, 238, 235, 224, 26, 51, 63, 115, 35, 84, 116, 148, 243, 8, 23], # contract address in int array
                                                      "amount": 30}, is_sync=False)
            if not txhash_sendmsg:
                print("✗ crossTransfer failed")
                sys.exit(1)
            print(f"[app crossTransfer]txhash:{txhash_sendmsg}")
        except Exception as e:
            self._abort("test transfer to eth failed", e, f"dapp={self.cross_transfer_dapp}")


    def get_contract_states(self):
        print("[am status]")
        print(json.dumps(self.client.get_contract_state(self.base_dapp, self.am_contract_name, Scope.Global, None).to_dict(), indent=4, ensure_ascii=False))
        print("[sdp status]")
        print(json.dumps(self.client.get_contract_state(self.base_dapp, self.sdp_contract_name, Scope.Global, None).to_dict(), indent=4, ensure_ascii=False))
        print("[monitor status]")
        print(json.dumps(self.client.get_contract_state(self.base_dapp, self.monitor_contract_name, Scope.Global, None).to_dict(), indent=4, ensure_ascii=False))

        auth_msg_contract_info = self.client.get_contract_info(self.base_dapp, self.am_contract_name)
        am_cid = auth_msg_contract_info.get("ContractVersionID")
        am_address = f"0x{am_cid:016X}:contract"
        print("am cid: " + str(am_cid))
        print("am addr: " + am_address)

        sdp_msg_contract_info = self.client.get_contract_info(self.base_dapp, self.sdp_contract_name)
        sdp_cid = sdp_msg_contract_info.get("ContractVersionID")
        sdp_address = f"0x{sdp_cid:016X}:contract"
        print("sdp cid: " + str(sdp_cid))
        print("sdp addr: " + sdp_address)

        monitor_msg_contract_info = self.client.get_contract_info(self.base_dapp, self.monitor_contract_name)
        monitor_cid = monitor_msg_contract_info.get("ContractVersionID")
        monitor_address = f"0x{monitor_cid:016X}:contract"
        print("monitor cid: " + str(monitor_cid))
        print("monitor addr: " + monitor_address)

        app_contract_info = self.client.get_contract_info(self.base_dapp, self.app_contract_name)
        app_cid = app_contract_info.get("ContractVersionID")
        app_address = f"0x{app_cid:016X}:contract"
        print("app cid: " + str(app_cid))
        print("app addr: " + app_address)

        # print(self.client.get_contract_state(self.cross_transfer_dapp,self.transfer_contract_name,Scope.Address,self.deployer.address))
        # print(self.client.get_contract_state(self.cross_transfer_dapp,self.transfer_contract_name,Scope.Address,"0x0000018300D00001:contract"))


def to_int_array_from_bytes(byte_arr) -> list[int]:
    int_arr = []
    for b in byte_arr:
        int_arr.append(b & 0xFF)
    return int_arr

if __name__ == "__main__":
    dapp = Deployer()
    dapp.initialize()

    # dapp.transfer_to_eth()

    # dapp.set_relayer_in_am()
    # dapp.get_am_sdp_cid()

    dapp.mint_tokens()
    dapp.create_dapp(BASE_DAPP)
    dapp.set_dapp_profile(BASE_DAPP)
    
    dapp.deploy_base_contracts()

    dapp.deploy_app_contract()

    dapp.get_contract_states()

    # dapp.mint_tokens()
    # dapp.create_dapp()
    # dapp.deploy_cross_transfer_contract()

    # dapp.faucet()
