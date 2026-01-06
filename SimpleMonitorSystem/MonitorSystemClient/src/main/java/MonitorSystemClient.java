import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import com.alipay.antchain.bridge.ptc.committee.monitor.system.grpc.*;

public class MonitorSystemClient {

    public static void main(String[] args) {
        String host = "localhost";
        int port = 50051;

        // 创建 gRPC 通道
        ManagedChannel channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()  // 明文传输
                .build();

        // 创建阻塞存根
        MonitorSystemServiceGrpc.MonitorSystemServiceBlockingStub stub =
                MonitorSystemServiceGrpc.newBlockingStub(channel);

        // 构造请求（这里用假数据）
        byte[] rawUcp = new byte[]{0x01, 0x02, 0x03, 0x04};  // 你可以替换成实际数据
        VerifyCrossChainMessageInMonitorSystemRequest request =
                VerifyCrossChainMessageInMonitorSystemRequest.newBuilder()
                        .setRawUcp(com.google.protobuf.ByteString.copyFrom(rawUcp))
                        .build();

        // 调用 gRPC 方法
        MonitorSystemResponse response = stub.verifyCrossChainMessageInMonitorSystem(request);

        // 打印返回
        System.out.println("收到响应:");
        System.out.println("code = " + response.getCode());
        System.out.println("errorMsg = " + response.getErrorMsg());
        if (response.hasVerifyCrossChainMessageInMonitorSystemResp()) {
            VerifyCrossChainMessageInMonitorSystemResponse resp = response.getVerifyCrossChainMessageInMonitorSystemResp();
            System.out.println("result = " + resp.getResult());
            System.out.println("msg = " + resp.getMsg());
        }

        // 关闭通道
        channel.shutdown();
    }
}
