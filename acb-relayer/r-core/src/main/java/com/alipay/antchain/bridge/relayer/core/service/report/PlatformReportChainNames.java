package com.alipay.antchain.bridge.relayer.core.service.report;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Projects protocol chain names into the reporting API's public vocabulary. */
final class PlatformReportChainNames {

    private static final String DOMAIN_SUFFIX = "([0-9]+(?:\\.[0-9]+)*)";
    private static final Pattern DOMAIN = Pattern.compile("diox" + DOMAIN_SUFFIX, Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCKCHAIN_ID = Pattern.compile("diox" + DOMAIN_SUFFIX + "\\.id", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRODUCT = Pattern.compile("dioxide([0-9]*)", Pattern.CASE_INSENSITIVE);

    private PlatformReportChainNames() {
    }

    static String domain(String value) {
        return alias(value, DOMAIN, "idea", "");
    }

    static String blockchainId(String value) {
        return alias(value, BLOCKCHAIN_ID, "idea", ".id");
    }

    static String product(String value) {
        return alias(value, PRODUCT, "idealchain", "");
    }

    private static String alias(String value, Pattern pattern, String prefix, String suffix) {
        if (value == null) {
            return null;
        }
        Matcher match = pattern.matcher(value);
        return match.matches() ? prefix + match.group(1) + suffix : value;
    }
}
