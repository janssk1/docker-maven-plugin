package io.fabric8.maven.docker.wait;

import java.io.IOException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.maven.docker.config.WaitConfiguration;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultHttpRequestRetryHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;

/**
 * Check whether a given URL is available
 *
 */
public class HttpPingChecker implements WaitChecker {

    private int statusMin;
    private int statusMax;
    private String url;
    private String method;
    private boolean allowAllHosts;

    // Disable HTTP client retries by default.
    private static final int HTTP_CLIENT_RETRIES = 0;

    // Timeout for pings
    private static final int HTTP_PING_TIMEOUT = 500;

    /**
     * Ping the given URL
     *
     * @param url URL to check
     * @param method HTTP method to use
     * @param status status code to check
     */
    public HttpPingChecker(String url, String method, String status) {
        this.url = url;
        this.method = method;

        Matcher matcher = Pattern.compile("^(\\d+)\\s*\\.\\.+\\s*(\\d+)$").matcher(status);
        if (matcher.matches()) {
            statusMin = Integer.parseInt(matcher.group(1));
            statusMax = Integer.parseInt(matcher.group(2));
        } else {
            statusMin = statusMax = Integer.parseInt(status);
        }
    }

    public HttpPingChecker(String waitUrl) {
        this(waitUrl, WaitConfiguration.DEFAULT_HTTP_METHOD, WaitConfiguration.DEFAULT_STATUS_RANGE);
    }

    public HttpPingChecker(String url, String method, String status, boolean allowAllHosts) {
        this(url, method, status);
        this.allowAllHosts = allowAllHosts;
    }

    @Override
    public boolean check() {
        try {
            return ping();
        } catch (IOException exception) {
            // Could occur and then the check is always considered as failed
            return false;
        }
    }

    private boolean ping() throws IOException {
        RequestConfig requestConfig =
                RequestConfig.custom()
                        .setSocketTimeout(HTTP_PING_TIMEOUT)
                        .setConnectTimeout(HTTP_PING_TIMEOUT)
                        .setConnectionRequestTimeout(HTTP_PING_TIMEOUT)
                        .setRedirectsEnabled(false)
                        .build();

        CloseableHttpClient httpClient;
        if (allowAllHosts) {
            SSLContextBuilder builder = new SSLContextBuilder();
            try {
                builder.loadTrustMaterial(null, new TrustSelfSignedStrategy());
                SSLConnectionSocketFactory socketFactory = new SSLConnectionSocketFactory(builder.build(), NoopHostnameVerifier.INSTANCE);
                httpClient = HttpClientBuilder.create()
                                              .setDefaultRequestConfig(requestConfig)
                                              .setRetryHandler(new DefaultHttpRequestRetryHandler(HTTP_CLIENT_RETRIES, false))
                                              .setSSLSocketFactory(socketFactory)
                                              .build();
            } catch (NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
                throw new IOException("Unable to set self signed strategy on http wait: " + e, e);
            }
        } else {
            httpClient = HttpClientBuilder.create()
                    .setDefaultRequestConfig(requestConfig)
                    .setRetryHandler(new DefaultHttpRequestRetryHandler(HTTP_CLIENT_RETRIES, false))
                    .build();
        }

        try {
            CloseableHttpResponse response = httpClient.execute(RequestBuilder.create(method.toUpperCase()).setUri(url).build());
            try {
                int responseCode = response.getStatusLine().getStatusCode();
                if (responseCode == 501) {
                    throw new IllegalArgumentException("Invalid or not supported HTTP method '" + method.toUpperCase() + "' for checking " + url);
                }
                return responseCode >= statusMin && responseCode <= statusMax;
            } finally {
                response.close();
            }
        } finally {
            httpClient.close();
        }
    }

    @Override
    public void cleanUp() {
        // No cleanup required for this checker
    }
}
