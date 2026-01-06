import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import com.alipay.antchain.bridge.ptc.committee.monitor.system.grpc.*;

import java.util.Scanner;

public class MonitorSystemServer {

    private Server server;

    // 控制返回成功或失败，默认返回成功
    private volatile boolean verifySuccess = true;

    public void start(int port) throws Exception {
        server = ServerBuilder.forPort(port)
                .addService(new MonitorSystemServiceImpl())
                .build()
                .start();

        System.out.println("gRPC server started, listening on " + port);

        // 添加 Ctrl+C 优雅关闭 hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\nShutting down gRPC server...");
            MonitorSystemServer.this.stop();
            System.out.println("Server shut down.");
        }));

        // 启动控制线程，监听终端输入
        new Thread(this::startCommandListener, "CommandListenerThread").start();
    }

    private void startCommandListener() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("请输入 'success' 或 'fail' 来控制 verifyCrossChainMessageInMonitorSystem 返回结果：");
        while (true) {
            String input = scanner.nextLine();
            if ("success".equalsIgnoreCase(input)) {
                verifySuccess = true;
                System.out.println("切换为：返回成功");
            } else if ("fail".equalsIgnoreCase(input)) {
                verifySuccess = false;
                System.out.println("切换为：返回失败");
            } else {
                System.out.println("无效指令，请输入 'success' 或 'fail'");
            }
        }
    }

    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    class MonitorSystemServiceImpl extends MonitorSystemServiceGrpc.MonitorSystemServiceImplBase {
        @Override
        public void heartbeat(Empty request, StreamObserver<MonitorSystemResponse> responseObserver) {
            MonitorSystemResponse response = MonitorSystemResponse.newBuilder()
                    .setCode(0)
                    .setErrorMsg("")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void verifyCrossChainMessageInMonitorSystem(
                VerifyCrossChainMessageInMonitorSystemRequest request,
                StreamObserver<MonitorSystemResponse> responseObserver) {

            byte[] rawUcp = request.getRawUcp().toByteArray();
            System.out.println("Received verifyCrossChainMessageInMonitorSystem request:");
            System.out.println("rawUcp (hex): " + bytesToHex(rawUcp));

            MonitorSystemResponse.Builder responseBuilder = MonitorSystemResponse.newBuilder()
                    .setCode(0)
                    .setErrorMsg("");

            if (verifySuccess) {
                responseBuilder.setVerifyCrossChainMessageInMonitorSystemResp(
                        VerifyCrossChainMessageInMonitorSystemResponse.newBuilder()
                                .setResult(0)
                                .setMsg("ok")
                                .build());
                System.out.println("返回：成功");
            } else {
                responseBuilder.setVerifyCrossChainMessageInMonitorSystemResp(
                        VerifyCrossChainMessageInMonitorSystemResponse.newBuilder()
                                .setResult(1)
                                .setMsg("fail")
                                .build());
                System.out.println("返回：失败");
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        }

        @Override
        public void relayUcpToMonitorSystem(
                RelayUcpToMonitorSystemRequest request,
                StreamObserver<MonitorSystemResponse> responseObserver) {

            byte[] rawUcp = request.getRawUcp().toByteArray();
            System.out.println("Received relayUcpToMonitorSystem request:");
            System.out.println("rawUcp (hex): " + bytesToHex(rawUcp));

            MonitorSystemResponse response = MonitorSystemResponse.newBuilder()
                    .setCode(0)
                    .setErrorMsg("")
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();

        }

        private String bytesToHex(byte[] bytes) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        }
    }

    public static void main(String[] args) throws Exception {
        int port = 50051;
        MonitorSystemServer server = new MonitorSystemServer();
        server.start(port);
        server.blockUntilShutdown(); // 阻塞，直到关闭
    }
}
