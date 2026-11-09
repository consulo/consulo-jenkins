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

import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * URLs of the Jenkins remote access API. Jobs and builds are identified by the URLs Jenkins reports for them; a queued
 * build that has not started yet is identified by its queue item, see {@link #queueBuildId(String)}.
 *
 * @author VISTALL
 * @since 2026-11-09
 */
public final class JenkinsUrls {
    public static final String QUEUE_BUILD_PREFIX = "queue:";

    private static final Pattern QUEUE_ITEM = Pattern.compile("/queue/item/(\\d+)/?$");

    /**
     * Path segments that start the object part of a Jenkins URL, everything before them is the root URL of Jenkins.
     */
    private static final List<String> OBJECT_SEGMENTS = List.of("/job/", "/view/", "/queue/", "/computer/", "/user/", "/me/");

    /**
     * Path segments of the objects a configured server URL may point to.
     */
    private static final List<String> CONTAINER_SEGMENTS = List.of("/job/", "/view/");

    private JenkinsUrls() {
    }

    public static String withSlash(String url) {
        return url.endsWith("/") ? url : url + "/";
    }

    public static String normalizeServerUrl(String serverUrl) {
        return withSlash(serverUrl.trim());
    }

    /**
     * Root URL of Jenkins for a server URL that may point to a view or a folder: everything before the first
     * {@code /job/} or {@code /view/}.
     */
    public static String rootUrl(String serverUrl) {
        String url = withSlash(serverUrl);
        int pathStart = url.indexOf("://");
        int from = url.indexOf('/', pathStart < 0 ? 0 : pathStart + 3);
        if (from < 0) {
            return url;
        }
        int end = -1;
        for (String segment : CONTAINER_SEGMENTS) {
            int index = url.indexOf(segment, from);
            if (index >= 0 && (end < 0 || index < end)) {
                end = index;
            }
        }
        return end < 0 ? url : url.substring(0, end + 1);
    }

    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * Encodes every segment of a relative path, keeps the slashes.
     */
    public static String encodePath(String path) {
        StringBuilder builder = new StringBuilder();
        for (String segment : path.split("/", -1)) {
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(encode(segment).replace("+", "%20"));
        }
        return builder.toString();
    }

    /**
     * {@code <object>/api/json?tree=<tree>} of a job, build, view, queue item or of the server itself.
     */
    public static String api(String objectUrl, String tree) {
        return withSlash(objectUrl) + "api/json?tree=" + encode(tree);
    }

    /**
     * Moves a URL reported by Jenkins to the root URL of the configured server. Jenkins builds absolute URLs from its
     * own root URL setting, which is not always reachable from here (reverse proxy, internal host name).
     */
    public static String rebase(String url, String rootUrl) {
        if (url.startsWith(rootUrl)) {
            return url;
        }

        int start = -1;
        for (String segment : OBJECT_SEGMENTS) {
            int index = url.indexOf(segment);
            if (index >= 0 && (start < 0 || index < start)) {
                start = index;
            }
        }
        if (start >= 0) {
            return rootUrl + url.substring(start + 1);
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        return rootUrl + (url.startsWith("/") ? url.substring(1) : url);
    }

    /**
     * Id of the queue item from the {@code Location} answer of {@code build} and {@code buildWithParameters}, or from a
     * queue item URL; -1 when the URL is not one of a queue item.
     */
    public static long queueId(@Nullable String url) {
        if (url == null) {
            return -1;
        }
        Matcher matcher = QUEUE_ITEM.matcher(url.trim());
        if (!matcher.find()) {
            return -1;
        }
        try {
            return Long.parseLong(matcher.group(1));
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    public static String queueItemUrl(String rootUrl, long queueId) {
        return withSlash(rootUrl) + "queue/item/" + queueId + "/";
    }

    public static String queueBuildId(String queueItemUrl) {
        return QUEUE_BUILD_PREFIX + queueItemUrl;
    }

    /**
     * @return the queue item URL when the build id is one of a queued build, otherwise {@code null}
     */
    public static @Nullable String queueItemUrlOf(String buildId) {
        return buildId.startsWith(QUEUE_BUILD_PREFIX) ? buildId.substring(QUEUE_BUILD_PREFIX.length()) : null;
    }

    /**
     * Host with port and context path, for example {@code ci.consulo.io} or {@code build.local:8080/jenkins}.
     */
    public static String presentableName(String serverUrl) {
        String url = serverUrl.trim();
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) {
                return url;
            }
            StringBuilder builder = new StringBuilder(host);
            if (uri.getPort() > 0) {
                builder.append(':').append(uri.getPort());
            }
            String path = uri.getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                builder.append(path.endsWith("/") ? path.substring(0, path.length() - 1) : path);
            }
            return builder.toString();
        }
        catch (IllegalArgumentException e) {
            return url;
        }
    }
}
