package com.alipay.antchain.bridge.plugins.lib.transactions;

import java.nio.ByteBuffer;
import java.sql.DriverManager;

/** Subprocess fixture only: no real node calls or credentials. */
public class CoordinatorProcessProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        JdbcTransactionCoordinator coordinator = new JdbcTransactionCoordinator(
                () -> DriverManager.getConnection(args[0], "root", ""), args[1], "fixture");
        for (int i = 0; i < Integer.parseInt(args[3]); i++) {
            String hash = coordinator.submit(args[2] + "-" + i, "account",
                    new byte[]{1, 2, 3}, new JdbcTransactionCoordinator.Transport() {
                        public String checkpoint() { return "fixture"; }
                        public long currentIsn(String account) { return 181; }
                        public byte[] composeAndSign(long isn) { return ByteBuffer.allocate(8).putLong(isn).array(); }
                        public String broadcast(byte[] signed) {
                            if (args.length > 4 && "crash".equals(args[4])) { Runtime.getRuntime().halt(17); }
                            return "tx-" + ByteBuffer.wrap(signed).getLong();
                        }
                    });
            System.out.println(hash);
        }
    }
}
