package com.alipay.antchain.bridge.relayer.core.service.report;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alipay.antchain.bridge.commons.core.base.SendResponseResult;
import com.alipay.antchain.bridge.relayer.commons.model.SDPMsgWrapper;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

public class PlatformReportClientTest {

    @Test
    public void testUcpIdIsFirstBodyFieldForInterfacesTwoToFour() throws Exception {
        CapturingRestTemplate restTemplate = new CapturingRestTemplate();
        PlatformReportClient client = new PlatformReportClient();
        setField(client, "enabled", true);
        setField(client, "apiKey", "test-api-key");
        setField(client, "endpoint", "http://platform.example");
        setField(client, "restTemplate", restTemplate);

        client.reportRegulation("ucp-123", "approved", "");

        SDPMsgWrapper message = new SDPMsgWrapper();
        message.setReceiverBlockchainProduct("ethereum");
        message.setReceiverBlockchainId("chain-2");
        SendResponseResult result = new SendResponseResult(
                "0xtx",
                true,
                true,
                "",
                "",
                123456789L,
                new byte[]{0x01, 0x02}
        );
        client.reportTargetChainSubmission("ucp-123", message, result);
        client.reportTargetChainExecution("ucp-123", result, false);

        Assert.assertEquals(3, restTemplate.requests.size());
        assertRequest(
                restTemplate.requests.get(0),
                "/api/cc-relayer/ucps/ucp-123/regulation-status"
        );
        assertRequest(
                restTemplate.requests.get(1),
                "/api/cc-relayer/ucps/ucp-123/target-chain-submission"
        );
        assertRequest(
                restTemplate.requests.get(2),
                "/api/cc-relayer/ucps/ucp-123/target-chain-execution"
        );
    }

    private void assertRequest(CapturedRequest request, String expectedPath) {
        Assert.assertEquals(expectedPath, request.path);
        Assert.assertTrue(request.body.startsWith("{\"ucpId\":\"ucp-123\","));
        JSONObject body = JSON.parseObject(request.body);
        Assert.assertEquals("ucp-123", body.getString("ucpId"));
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static class CapturedRequest {

        private final String path;
        private final String body;

        private CapturedRequest(String path, String body) {
            this.path = path;
            this.body = body;
        }
    }

    private static class CapturingRestTemplate extends RestTemplate {

        private final List<CapturedRequest> requests = new ArrayList<>();

        @Override
        @SuppressWarnings("unchecked")
        public <T> ResponseEntity<T> exchange(
                String url,
                HttpMethod method,
                HttpEntity<?> requestEntity,
                Class<T> responseType,
                Object... uriVariables
        ) {
            Assert.assertEquals(HttpMethod.POST, method);
            Assert.assertEquals("test-api-key", requestEntity.getHeaders().getFirst("x-api-key"));
            requests.add(
                    new CapturedRequest(
                            URI.create(url).getPath(),
                            String.valueOf(requestEntity.getBody())
                    )
            );
            return (ResponseEntity<T>) new ResponseEntity<>("{\"code\":0}", HttpStatus.OK);
        }
    }
}
