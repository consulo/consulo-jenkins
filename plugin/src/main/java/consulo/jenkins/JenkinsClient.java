/*
 * Copyright (c) 2013 David Boissier
 * Copyright 2013-2026 consulo.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package consulo.jenkins;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import consulo.accountManagement.AccountConnection;
import consulo.accountManagement.AccountRequestDecorator;
import consulo.http.client.HttpApiHelper;
import consulo.http.client.HttpStatusErrorException;
import consulo.jenkins.localize.JenkinsLocalize;
import consulo.logging.Logger;
import consulo.util.lang.StringUtil;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Remote access API of one Jenkins server, signed in with an account connection. Requests that change something are
 * POSTs with a crumb: Jenkins exempts API token requests from CSRF protection, but not anonymous ones and not the ones
 * signed in with a password, and binds the crumb to the web session that issued it.
 *
 * @author VISTALL
 * @since 2026-11-09
 */
public final class JenkinsClient {
    private static final Logger LOG = Logger.getInstance(JenkinsClient.class);
    private static final HttpApiHelper HTTP = HttpApiHelper.create(LOG);

    private static final String CRUMB_ERROR = "No valid crumb was included in the request";
    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded; charset=UTF-8";

    private record Crumb(@Nullable String field, @Nullable String value, @Nullable String cookie) {
        static final Crumb NONE = new Crumb(null, null, null);
    }

    private final AccountConnection myConnection;
    private final String myServerUrl;
    private final String myRootUrl;

    private volatile @Nullable CompletableFuture<Crumb> myCrumb;

    private JenkinsClient(AccountConnection connection, String serverUrl) {
        myConnection = connection;
        myServerUrl = serverUrl;
        myRootUrl = JenkinsUrls.rootUrl(serverUrl);
    }

    /**
     * @param serverUrl the server URL of the CI options, {@code null} or empty for the one of the account
     */
    public static JenkinsClient create(AccountConnection connection, @Nullable String serverUrl) {
        String url = StringUtil.isEmptyOrSpaces(serverUrl) ? connection.getServerUrl() : serverUrl;
        if (url == null || StringUtil.isEmptyOrSpaces(url)) {
            throw new IllegalStateException(JenkinsLocalize.errorServerUrlNotSet().get());
        }
        return new JenkinsClient(connection, JenkinsUrls.normalizeServerUrl(url));
    }

    /**
     * The configured server URL, always with a trailing slash. It is the root of Jenkins or a view or folder whose jobs
     * are the top level ones.
     */
    public String getServerUrl() {
        return myServerUrl;
    }

    /**
     * Root URL of Jenkins, always with a trailing slash: the queue, the crumb issuer, views and users are here.
     */
    public String getRootUrl() {
        return myRootUrl;
    }

    public CompletableFuture<JsonObject> getJson(String url) {
        return request(url).thenCompose(builder -> {
            builder.GET().header("Accept", "application/json");
            return HTTP.sendAndReadBody(builder.build(), JenkinsClient::readJson);
        });
    }

    /**
     * Text with the answer headers, for the progressive log.
     */
    public CompletableFuture<HttpResponse<String>> getText(String url) {
        return request(url).thenCompose(builder -> HTTP.sendAndRead(builder.GET().build(), JenkinsClient::readText));
    }

    public CompletableFuture<Path> download(String url, Path target) {
        return request(url).thenCompose(builder -> HTTP.sendAndReadBody(builder.GET().build(), (stream, mimeType, charset) -> {
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }));
    }

    public CompletableFuture<HttpResponse<String>> post(String url) {
        return post(url, null, HttpRequest.BodyPublishers::noBody);
    }

    public CompletableFuture<HttpResponse<String>> postForm(String url, Map<String, String> values) {
        StringBuilder form = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!form.isEmpty()) {
                form.append('&');
            }
            form.append(JenkinsUrls.encode(entry.getKey())).append('=').append(JenkinsUrls.encode(entry.getValue()));
        }
        String body = form.toString();
        return post(url, FORM_CONTENT_TYPE, () -> HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    }

    /**
     * Multipart form with a {@code json} field and file parts, like the job page of Jenkins sends it.
     *
     * @param files file parts by their part name, the {@code json} field refers to them by these names
     */
    public CompletableFuture<HttpResponse<String>> postMultipart(String url, String json, Map<String, Path> files) {
        String boundary = "----consulo" + UUID.randomUUID().toString().replace("-", "");
        Callable<HttpRequest.BodyPublisher> body = () -> {
            List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
            for (Map.Entry<String, Path> file : files.entrySet()) {
                String fileName = String.valueOf(file.getValue().getFileName()).replace("\"", "");
                parts.add(HttpRequest.BodyPublishers.ofString(
                    "--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + file.getKey() + "\"; filename=\"" + fileName + "\"\r\n"
                        + "Content-Type: application/octet-stream\r\n\r\n",
                    StandardCharsets.UTF_8
                ));
                parts.add(HttpRequest.BodyPublishers.ofFile(file.getValue()));
                parts.add(HttpRequest.BodyPublishers.ofString("\r\n", StandardCharsets.UTF_8));
            }
            parts.add(HttpRequest.BodyPublishers.ofString(
                "--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"json\"\r\n"
                    + "Content-Type: application/json; charset=UTF-8\r\n\r\n"
                    + json + "\r\n"
                    + "--" + boundary + "--\r\n",
                StandardCharsets.UTF_8
            ));
            return HttpRequest.BodyPublishers.concat(parts.toArray(HttpRequest.BodyPublisher[]::new));
        };
        return post(url, "multipart/form-data; boundary=" + boundary, body);
    }

    private CompletableFuture<HttpResponse<String>> post(String url,
                                                         @Nullable String contentType,
                                                         Callable<HttpRequest.BodyPublisher> body) {
        return postOnce(url, contentType, body).exceptionallyCompose(error -> {
            if (isCrumbError(error)) {
                // the web session of the crumb expired
                myCrumb = null;
                return postOnce(url, contentType, body);
            }
            return CompletableFuture.failedFuture(error);
        });
    }

    private CompletableFuture<HttpResponse<String>> postOnce(String url,
                                                             @Nullable String contentType,
                                                             Callable<HttpRequest.BodyPublisher> body) {
        return crumb().thenCompose(crumb -> request(url).thenCompose(builder -> {
            try {
                builder.POST(body.call());
            }
            catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            String field = crumb.field();
            String value = crumb.value();
            if (field != null && value != null) {
                builder.header(field, value);
            }
            String cookie = crumb.cookie();
            if (cookie != null) {
                builder.header("Cookie", cookie);
            }
            return HTTP.sendAndRead(builder.build(), JenkinsClient::readText);
        }));
    }

    private CompletableFuture<Crumb> crumb() {
        CompletableFuture<Crumb> crumb = myCrumb;
        if (crumb == null || crumb.isCompletedExceptionally() || crumb.isCancelled()) {
            crumb = loadCrumb();
            myCrumb = crumb;
        }
        return crumb;
    }

    private CompletableFuture<Crumb> loadCrumb() {
        return request(myRootUrl + "crumbIssuer/api/json")
            .thenCompose(builder -> {
                builder.GET().header("Accept", "application/json");
                return HTTP.sendAndRead(builder.build(), JenkinsClient::readJson);
            })
            .handle((response, error) -> {
                if (error != null) {
                    if (!isStatus(error, 404)) {
                        // the POST itself reports what is wrong
                        LOG.info("Cannot get a crumb from " + myRootUrl, unwrap(error));
                    }
                    // 404: CSRF protection is off
                    return Crumb.NONE;
                }
                JsonObject json = response.body();
                String field = JenkinsJson.string(json, "crumbRequestField");
                return new Crumb(field, JenkinsJson.string(json, "crumb"), cookies(response.headers()));
            });
    }

    private CompletableFuture<HttpRequest.Builder> request(String url) {
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> parameters = new LinkedHashMap<>();
        myConnection.decorate(new AccountRequestDecorator() {
            @Override
            public void header(String name, String value) {
                headers.put(name, value);
            }

            @Override
            public void queryParameter(String name, String value) {
                parameters.put(name, value);
            }
        });

        StringBuilder uri = new StringBuilder(JenkinsUrls.rebase(url, myRootUrl));
        for (Map.Entry<String, String> parameter : parameters.entrySet()) {
            uri.append(uri.indexOf("?") < 0 ? '?' : '&')
                .append(JenkinsUrls.encode(parameter.getKey()))
                .append('=')
                .append(JenkinsUrls.encode(parameter.getValue()));
        }

        CompletableFuture<HttpRequest.Builder> request;
        try {
            request = HTTP.request(uri.toString());
        }
        catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return request.thenApply(builder -> {
            headers.forEach(builder::header);
            return builder;
        });
    }

    public static @Nullable String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).orElse(null);
    }

    public static boolean isStatus(Throwable error, int status) {
        return unwrap(error) instanceof HttpStatusErrorException statusError && statusError.getStatusCode() == status;
    }

    public static Throwable unwrap(Throwable error) {
        Throwable result = error;
        while (result instanceof CompletionException && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }

    private static boolean isCrumbError(Throwable error) {
        if (!(unwrap(error) instanceof HttpStatusErrorException statusError) || statusError.getStatusCode() != 403) {
            return false;
        }
        String body = statusError.getBody();
        return body != null && body.contains(CRUMB_ERROR);
    }

    private static @Nullable String cookies(HttpHeaders headers) {
        List<String> cookies = new ArrayList<>();
        for (String setCookie : headers.allValues("Set-Cookie")) {
            int end = setCookie.indexOf(';');
            String cookie = (end < 0 ? setCookie : setCookie.substring(0, end)).trim();
            if (!cookie.isEmpty()) {
                cookies.add(cookie);
            }
        }
        return cookies.isEmpty() ? null : String.join("; ", cookies);
    }

    private static String readText(InputStream stream, @Nullable String mimeType, @Nullable Charset charset) throws IOException {
        return new String(stream.readAllBytes(), charset == null ? StandardCharsets.UTF_8 : charset);
    }

    private static JsonObject readJson(InputStream stream, @Nullable String mimeType, @Nullable Charset charset) throws IOException {
        String text = readText(stream, mimeType, charset);
        JsonElement element;
        try {
            element = JsonParser.parseString(text);
        }
        catch (RuntimeException e) {
            throw new IOException(JenkinsLocalize.errorNotJson(StringUtil.first(text.trim(), 120, true)).get(), e);
        }
        if (!element.isJsonObject()) {
            throw new IOException(JenkinsLocalize.errorNotJson(StringUtil.first(text.trim(), 120, true)).get());
        }
        return element.getAsJsonObject();
    }
}
