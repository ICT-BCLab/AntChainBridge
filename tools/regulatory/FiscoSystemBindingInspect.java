/* SPDX-License-Identifier: Apache-2.0 */
package tools.regulatory;

import com.alipay.antchain.bridge.commons.bbc.DefaultBBCContext;
import com.alipay.antchain.bridge.plugins.fiscobcos.FISCOBCOSBBCService;
import com.alipay.antchain.bridge.plugins.fiscobcos.abi.Monitor;
import com.alipay.antchain.bridge.plugins.fiscobcos.abi.MonitorVerifier;
import com.alipay.antchain.bridge.plugins.fiscobcos.abi.PtcHub;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only live inspection of the FISCO PTC/MonitorVerifier bindings. */
public final class FiscoSystemBindingInspect {

    private FiscoSystemBindingInspect() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("expected CONFIG_JSON PTC_HUB MONITOR");
        }
        DefaultBBCContext context = new DefaultBBCContext();
        context.setConfForBlockchainClient(Files.readAllBytes(Path.of(args[0])));
        FISCOBCOSBBCService service = new FISCOBCOSBBCService();
        boolean started = false;
        try {
            service.startup(context);
            started = true;
            Monitor monitor = Monitor.load(args[2], service.getClient(), service.getKeyPair());
            String verifierAddress = monitor.getMonitorVerifier();
            PtcHub ptcHub = PtcHub.load(args[1], service.getClient(), service.getKeyPair());
            MonitorVerifier verifier = MonitorVerifier.load(
                    verifierAddress, service.getClient(), service.getKeyPair());
            System.out.println("monitor.version=" + monitor.getImplementationVersion());
            System.out.println("monitor.control=" + monitor.getMonitorControl());
            System.out.println("monitor.sdp=" + monitor.sdpAddress());
            System.out.println("monitor.verifier=" + verifierAddress);
            System.out.println("ptc.monitorVerifier=" + ptcHub.getMonitorVerifier());
            System.out.println("verifier.ptcHub=" + verifier.getPtcHubAddress());
        } finally {
            if (started) {
                service.shutdown();
            }
        }
    }
}
