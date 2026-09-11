import com.alipay.antchain.bridge.commons.core.base.CrossChainDomain;
import com.alipay.antchain.bridge.commons.core.base.CrossChainMessage;
import com.alipay.antchain.bridge.commons.core.base.UniformCrosschainPacket;
import com.alipay.antchain.bridge.ptc.committee.monitor.system.grpc.MonitorSystemResponse;
import com.alipay.antchain.bridge.ptc.committee.monitor.system.grpc.RelayUcpToMonitorSystemRequest;
import com.alipay.antchain.bridge.ptc.committee.monitor.system.grpc.VerifyCrossChainMessageInMonitorSystemRequest;
import com.google.protobuf.ByteString;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class MonitorSystemServerTest {

    private MonitorSystemServer server;

    private MonitorSystemServer.MonitorSystemServiceImpl service;

    private byte[] validRawUcp;

    @Before
    public void setUp() {
        server = new MonitorSystemServer();
        service = server.new MonitorSystemServiceImpl();
        validRawUcp = new UniformCrosschainPacket(
                new CrossChainDomain("test-domain"),
                CrossChainMessage.createCrossChainMessage(
                        CrossChainMessage.CrossChainMessageType.DEVELOPER_DESIGN,
                        1L,
                        1L,
                        new byte[]{1},
                        new byte[]{2},
                        new byte[]{3},
                        new byte[]{4},
                        new byte[]{5}
                ),
                null
        ).encode();
    }

    @Test
    public void testVerifySuccessAndFailureResponses() {
        VerifyCrossChainMessageInMonitorSystemRequest request = verifyRequest(validRawUcp);

        server.setVerifyMode(MonitorSystemServer.VerifyMode.SUCCESS);
        MonitorSystemResponse response = service.buildVerifyResponse(request);
        Assert.assertEquals(0, response.getCode());
        Assert.assertEquals("", response.getErrorMsg());
        Assert.assertTrue(response.hasVerifyCrossChainMessageInMonitorSystemResp());
        Assert.assertEquals(0, response.getVerifyCrossChainMessageInMonitorSystemResp().getResult());
        Assert.assertEquals("ok", response.getVerifyCrossChainMessageInMonitorSystemResp().getMsg());

        server.setVerifyMode(MonitorSystemServer.VerifyMode.FAILURE);
        response = service.buildVerifyResponse(request);
        Assert.assertEquals(0, response.getCode());
        Assert.assertEquals("", response.getErrorMsg());
        Assert.assertTrue(response.hasVerifyCrossChainMessageInMonitorSystemResp());
        Assert.assertEquals(1, response.getVerifyCrossChainMessageInMonitorSystemResp().getResult());
        Assert.assertFalse(response.getVerifyCrossChainMessageInMonitorSystemResp().getMsg().isEmpty());
    }

    @Test
    public void testInvalidRawUcpReturns400WithoutVerifyResponse() {
        MonitorSystemResponse response = service.buildVerifyResponse(verifyRequest(new byte[]{1, 2, 3, 4}));

        Assert.assertEquals(400, response.getCode());
        Assert.assertTrue(response.getErrorMsg().contains("failed to decode rawUcp"));
        Assert.assertFalse(response.hasVerifyCrossChainMessageInMonitorSystemResp());
    }

    @Test
    public void testForced500And503ResponsesDoNotContainVerifyResponse() {
        VerifyCrossChainMessageInMonitorSystemRequest request = verifyRequest(validRawUcp);

        server.setVerifyMode(MonitorSystemServer.VerifyMode.INTERNAL_ERROR);
        MonitorSystemResponse response = service.buildVerifyResponse(request);
        Assert.assertEquals(500, response.getCode());
        Assert.assertFalse(response.getErrorMsg().isEmpty());
        Assert.assertFalse(response.hasVerifyCrossChainMessageInMonitorSystemResp());

        server.setVerifyMode(MonitorSystemServer.VerifyMode.UNAVAILABLE);
        response = service.buildVerifyResponse(request);
        Assert.assertEquals(503, response.getCode());
        Assert.assertFalse(response.getErrorMsg().isEmpty());
        Assert.assertFalse(response.hasVerifyCrossChainMessageInMonitorSystemResp());
    }

    @Test
    public void testRelayAlwaysReturnsSuccessWithoutVerifyResponse() {
        for (byte[] rawUcp : new byte[][]{validRawUcp, new byte[]{1, 2, 3, 4}}) {
            MonitorSystemResponse response = service.buildRelayResponse(
                    RelayUcpToMonitorSystemRequest.newBuilder()
                            .setRawUcp(ByteString.copyFrom(rawUcp))
                            .setUcpId("ucp-id")
                            .build()
            );
            Assert.assertEquals(0, response.getCode());
            Assert.assertEquals("", response.getErrorMsg());
            Assert.assertFalse(response.hasVerifyCrossChainMessageInMonitorSystemResp());
        }

        MonitorSystemResponse responseAfterInternalError = service.buildRelayResponse(null);
        Assert.assertEquals(0, responseAfterInternalError.getCode());
        Assert.assertEquals("", responseAfterInternalError.getErrorMsg());
        Assert.assertFalse(responseAfterInternalError.hasVerifyCrossChainMessageInMonitorSystemResp());
    }

    private VerifyCrossChainMessageInMonitorSystemRequest verifyRequest(byte[] rawUcp) {
        return VerifyCrossChainMessageInMonitorSystemRequest.newBuilder()
                .setRawUcp(ByteString.copyFrom(rawUcp))
                .setUcpId("ucp-id")
                .build();
    }
}
