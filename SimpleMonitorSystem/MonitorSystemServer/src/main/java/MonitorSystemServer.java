import io.grpc.Server;
import io.grpc.stub.StreamObserver;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import com.alipay.antchain.bridge.ptc.committee.monitor.system.grpc.*;

import java.io.File;
import java.io.IOException;
import java.util.Scanner;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

public class MonitorSystemServer {

    private static final Logger LOGGER = createLogger();

    private Server server;

    // 控制返回成功或失败，默认返回成功
    private volatile boolean verifySuccess = true;

    public void start(int port) throws Exception {
        server = NettyServerBuilder.forPort(port)
                .useTransportSecurity(
                        new File("tls_certs/monitor-system.crt"),
                        new File("tls_certs/monitor-system.key")
                )
                .addService(new MonitorSystemServiceImpl())
                .build()
                .start();

        logInfo("gRPC TLS server started, listening on " + port);

        // 添加 Ctrl+C 优雅关闭 hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logInfo("Shutting down gRPC server...");
            MonitorSystemServer.this.stop();
            logInfo("Server shut down.");
        }));

        // 仅在交互式终端中启动控制线程。systemd/nohup 后台运行时没有可用的 stdin，
        // 此时保持默认的验证成功行为，避免 Scanner.nextLine() 因 EOF 退出并打印异常。
        if (System.console() != null) {
            new Thread(this::startCommandListener, "CommandListenerThread").start();
        } else {
            logInfo("No interactive console detected; command listener disabled and verify result defaults to success.");
        }
    }

    private void startCommandListener() {
        Scanner scanner = new Scanner(System.in);
        logInfo("请输入 'success' 或 'fail' 来控制 verifyCrossChainMessageInMonitorSystem 返回结果：");
        while (true) {
            String input = scanner.nextLine();
            if ("success".equalsIgnoreCase(input)) {
                verifySuccess = true;
                logInfo("切换为：返回成功");
            } else if ("fail".equalsIgnoreCase(input)) {
                verifySuccess = false;
                logInfo("切换为：返回失败");
            } else {
                logInfo("无效指令，请输入 'success' 或 'fail'");
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
            logInfo("Received verifyCrossChainMessageInMonitorSystem request:");
            logInfo("rawUcp (hex): " + bytesToHex(rawUcp));

            MonitorSystemResponse.Builder responseBuilder = MonitorSystemResponse.newBuilder()
                    .setCode(0)
                    .setErrorMsg("");

            if (verifySuccess) {
                responseBuilder.setVerifyCrossChainMessageInMonitorSystemResp(
                        VerifyCrossChainMessageInMonitorSystemResponse.newBuilder()
                                .setResult(0)
                                .setMsg("ok")
                                .build());
                logInfo("返回：成功");
            } else {
                responseBuilder.setVerifyCrossChainMessageInMonitorSystemResp(
                        VerifyCrossChainMessageInMonitorSystemResponse.newBuilder()
                                .setResult(1)
                                .setMsg("fail")
                                .build());
                logInfo("返回：失败");
            }

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        }

        @Override
        public void relayUcpToMonitorSystem(
                RelayUcpToMonitorSystemRequest request,
                StreamObserver<MonitorSystemResponse> responseObserver) {

            byte[] rawUcp = request.getRawUcp().toByteArray();
            logInfo("Received relayUcpToMonitorSystem request:");
            logInfo("rawUcp (hex): " + bytesToHex(rawUcp));

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

    private static void logInfo(String message) {
        LOGGER.info(message);
    }

    private static Logger createLogger() {
        Logger logger = Logger.getLogger(MonitorSystemServer.class.getName());
        logger.setUseParentHandlers(false);
        logger.setLevel(Level.INFO);

        for (Handler handler : logger.getHandlers()) {
            logger.removeHandler(handler);
        }

        Formatter formatter = new Formatter() {
            @Override
            public String format(LogRecord record) {
                return String.format("%1$tF %1$tT [%2$s] %3$s%n",
                        record.getMillis(),
                        record.getLevel().getName(),
                        record.getMessage());
            }
        };

        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.INFO);
        consoleHandler.setFormatter(formatter);
        logger.addHandler(consoleHandler);

        try {
            File logDir = new File("logs");
            if (!logDir.exists() && !logDir.mkdirs()) {
                throw new IOException("failed to create logs directory");
            }
            FileHandler fileHandler = new FileHandler("logs/monitor-system.log", true);
            fileHandler.setLevel(Level.INFO);
            fileHandler.setFormatter(formatter);
            logger.addHandler(fileHandler);
        } catch (IOException e) {
            consoleHandler.publish(new LogRecord(Level.WARNING,
                    "failed to initialize file logger: " + e.getMessage()));
        }

        return logger;
    }

    public static void main(String[] args) throws Exception {
        int port = 50051;
        MonitorSystemServer server = new MonitorSystemServer();
        server.start(port);
        server.blockUntilShutdown(); // 阻塞，直到关闭
    }
}
