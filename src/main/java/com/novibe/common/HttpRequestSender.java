package com.novibe.common;

import com.google.gson.Gson;
import com.novibe.common.base_structures.DnsProfile;
import com.novibe.common.base_structures.Jsonable;
import com.novibe.common.exception.DnsHttpError;
import com.novibe.common.util.Log;
import lombok.Setter;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Semaphore;

import static java.util.Objects.isNull;

public abstract class HttpRequestSender {

    private static final int TOO_MANY_REQUESTS = 429;
    private static final int MAX_RATE_LIMIT_RETRIES = 20;
    private static final long RATE_LIMIT_WAIT_MILLIS = 70_000L;

    private final Semaphore semaphore = new Semaphore(100);

    protected static final String GET = "GET";
    protected static final String POST = "POST";
    protected static final String DELETE = "DELETE";

    protected abstract String apiUrl();

    protected abstract String authHeaderName();

    protected abstract String authHeaderValue();

    protected abstract void react401();
    protected abstract void react403();
    protected abstract void react404(DnsHttpError dnsHttpError);

    @Setter(onMethod_ = @Autowired)
    protected HttpClient httpClient;

    @Setter(onMethod_ = @Autowired)
    protected Gson jsonMapper;

    @Setter(onMethod_ = @Autowired)
    protected DnsProfile dnsProfile;

    public <T> T get(String path, Class<T> responseType) {
        return sendRequest(GET, path, null, responseType);
    }

    public <T, R extends Jsonable> T post(String path, R requestBody, Class<T> responseType) {
        return sendRequest(POST, path, requestBody, responseType);
    }

    public <T> T delete(String path, Class<T> responseType) {
        return sendRequest(DELETE, path, null, responseType);

    }

    @SneakyThrows
    protected <T, R extends Jsonable> T sendRequest(String method, String path, R body, Class<T> responseBody) {
        URI uri = URI.create(apiUrl() + (isNull(path) ? "" : path));
        HttpRequest.BodyPublisher requestBody;
        if (isNull(body)) {
            requestBody = HttpRequest.BodyPublishers.noBody();
        } else {
            requestBody = HttpRequest.BodyPublishers.ofString(body.toJson());
        }

        for (int attempt = 0; attempt <= MAX_RATE_LIMIT_RETRIES; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header(authHeaderName(), authHeaderValue())
                    .header("Content-Type", "application/json")
                    .method(method, requestBody)
                    .build();

            semaphore.acquire();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } finally {
                semaphore.release();
            }

            if (response.statusCode() == TOO_MANY_REQUESTS && attempt < MAX_RATE_LIMIT_RETRIES) {
                Log.fail("NextDNS API rate limit reached. Waiting 70 seconds before retry...");
                Thread.sleep(RATE_LIMIT_WAIT_MILLIS);
                continue;
            }

            if (response.statusCode() > 299) {
                DnsHttpError httpError = new DnsHttpError(response, body);
                Log.fail(httpError.getMessage());
                switch (response.statusCode()) {
                    case 401 -> react401();
                    case 403 -> react403();
                    case 404 -> react404(httpError);
                    default -> throw httpError;
                }
            }
            if (response.body().isEmpty()) {
                return null;
            }
            return jsonMapper.fromJson(response.body(), responseBody);
        }
        throw new IllegalStateException("Unexpected HTTP retry state");
    }
}
