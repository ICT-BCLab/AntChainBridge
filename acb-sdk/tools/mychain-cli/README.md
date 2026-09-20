# Existing Mychain sender and native receipt tools

These tools call an existing EVM application by its **32-byte Identity**, and distinguish a query
error from an executed transaction. They do not deploy contracts or change Monitor/ACL settings.

## Build

Use Java 8 and a reviewed `mychain020-bbc` plugin JAR that provides
`Mychain020Client.callContract(Identity, Parameters, boolean)`. An old JAR providing only the
String overload will fail compilation; do not substitute the Identity hex string into that overload.

```sh
export JAVA_HOME=/path/to/jdk8
export MYCHAIN_PLUGIN_JAR=/path/to/reviewed/mychain020-bbc-plugin.jar
./build.sh
```

The build runs nine receipt regressions against the actual SDK JSON decoder: 404 with a default
zero receipt, 408 rejection, 413/414 pending, missing block height, confirmed success and revert,
the uint64-max sentinel, and a missing response. Successful execution of these tools does not
verify a cross-chain transfer; inspect the UCP, regulation, target receipt and application message.

## Inspect and send

```sh
java -cp "build:$MYCHAIN_PLUGIN_JAR" MychainExistingSender \
  /root-only/my02.json SOURCE_IDENTITY eth04 TARGET_IDENTITY UNIQUE_MESSAGE CHECK_ONLY
java -cp "build:$MYCHAIN_PLUGIN_JAR" MychainExistingSender \
  /root-only/my02.json SOURCE_IDENTITY eth04 TARGET_IDENTITY UNIQUE_MESSAGE \
  SEND_ONCE /root-only/new-attempt.txt
java -cp "build:$MYCHAIN_PLUGIN_JAR" MychainReceiptQuery /root-only/my02.json TX_HASH
```

`SOURCE_IDENTITY`, `TARGET_IDENTITY` and `TX_HASH` accept exactly 64 hex digits, optionally prefixed
with `0x`. The target Identity is a protocol bytes32 value (Ethereum addresses require their normal
12-byte left padding). The application must implement `sendUnordered(identity,string,bytes)`.

The attempt file is created atomically, with mode 0600, **before** submission. An existing file
prevents submission even if the previous process died or the result was unknown. After receiving
any response, the hash and status are appended. Do not remove the file to retry a transaction whose
outcome is unknown; reconcile the original hash. A different file is not permission to duplicate
an existing business request. Only a genuinely new authorized message uses a new attempt file.

The query tool prints `NATIVE_RECEIPT_FOUND=True` only when the outer SDK response succeeded,
the block number is valid and a receipt exists. An executed revert has `FOUND=True` with
`STATUS=EXECUTED_FAILED`; receipt existence is not execution success. No raw transactions,
credentials or complete UCPs are printed.

## 2026-09-20 incident

- **Wrong contract address.** The ad hoc `MychainExistingSender` passed Identity
  `2c8363f71d100ad44ff3ef2e8dba3e333a0df426d7200a3b9fb39913f601c3db` into the String/name API.
  The deployed SDK hashed that text to
  `f9b9e2f09e3cbfb1090b7ec97d78cf810fdf8b81ab30ca67c4dc593a27f0fee1`.
  Read-only queries found an EVM contract at the first Identity and no contract at the second.
- **Not an HTTP timeout.** The actual installed SDK maps 408 to `transaction verify failed`,
  and 404 to `query msg no result`. Both source attempts (`b99c1080…deb1d`, `b2037b87…b4144`)
  returned 408 before confirmation; subsequent receipt queries returned 404 while blocks advanced.
- **False receipt diagnosis.** The old script treated any non-null receipt object as found.
  The SDK populates an empty default object even on a 404 response. The outer return code and
  valid block evidence must be checked before using its `result=0`.
- **Independent earlier ACL failure.** An earlier newly deployed sender reached the Relayer but
  was denied by ACL. That is distinct from the later address-construction failure; neither
  widening ACL nor increasing a timeout fixes the double hashing.

The first repair deployment uses the reviewed Mychain020 JAR already running on the plugin
server, copied into the standalone CLI release. No core services need to restart. Preserve the
old CLI sources/classes/JAR in a root-only backup before replacing the active helper. Restore
only those files to roll back; do not replay historical attempts or alter chain state.
