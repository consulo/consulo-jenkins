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

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import consulo.accountManagement.Account;
import consulo.accountManagement.AccountManager;
import consulo.continuousIntegration.CIBuild;
import consulo.continuousIntegration.CIBuildStage;
import consulo.continuousIntegration.CIBuildStatus;
import consulo.continuousIntegration.CICapability;
import consulo.continuousIntegration.CIJob;
import consulo.continuousIntegration.CILogChunk;
import consulo.continuousIntegration.CIParameterDefinition;
import consulo.continuousIntegration.CIParameterValue;
import consulo.continuousIntegration.CIRunRequest;
import consulo.continuousIntegration.CIServer;
import consulo.continuousIntegration.CIServerType;
import consulo.continuousIntegration.support.CIArtifact;
import consulo.continuousIntegration.support.CIArtifactsSupport;
import consulo.continuousIntegration.support.CIBuildStagesSupport;
import consulo.continuousIntegration.support.CIRerunSupport;
import consulo.continuousIntegration.support.CIView;
import consulo.continuousIntegration.support.CIViewsSupport;
import consulo.http.client.HttpApiResponse;
import consulo.jenkins.localize.JenkinsLocalize;
import consulo.project.Project;
import consulo.util.lang.StringUtil;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * One Jenkins server. Jobs and builds are identified by their URLs; a started build is first a queue item
 * ({@link JenkinsUrls#queueBuildId(String)}) until an executor takes it.
 *
 * @author VISTALL
 * @since 2026-11-09
 */
public class JenkinsServer implements CIServer, CIViewsSupport, CIBuildStagesSupport, CIArtifactsSupport, CIRerunSupport {
    private static final Set<CICapability> CAPABILITIES =
        Set.of(CICapability.RUN, CICapability.STOP, CICapability.LOG, CICapability.PROGRESSIVE_LOG);

    /**
     * Builds searched for the one a forgotten queue item became.
     */
    private static final int QUEUE_LOOKUP_BUILDS = 25;

    private final JenkinsServerType myType;
    private final Project myProject;
    private final JenkinsServerOptions myOptions;
    private final AccountManager myAccountManager;

    private volatile @Nullable CompletableFuture<JenkinsClient> myClient;

    public JenkinsServer(JenkinsServerType type, Project project, JenkinsServerOptions options, AccountManager accountManager) {
        myType = type;
        myProject = project;
        myOptions = options;
        myAccountManager = accountManager;
    }

    @Override
    public CIServerType<?> getType() {
        return myType;
    }

    @Override
    public JenkinsServerOptions getOptions() {
        return myOptions;
    }

    @Override
    public String getName() {
        return StringUtil.isEmptyOrSpaces(myOptions.serverUrl)
            ? myType.getPresentableName().get()
            : JenkinsUrls.presentableName(myOptions.serverUrl);
    }

    @Override
    public @Nullable String getWebUrl() {
        return StringUtil.isEmptyOrSpaces(myOptions.serverUrl) ? null : JenkinsUrls.normalizeServerUrl(myOptions.serverUrl);
    }

    @Override
    public Set<CICapability> getCapabilities() {
        return CAPABILITIES;
    }

    private CompletableFuture<JenkinsClient> client() {
        CompletableFuture<JenkinsClient> client = myClient;
        if (client == null || client.isCompletedExceptionally() || client.isCancelled()) {
            client = connect();
            myClient = client;
        }
        return client;
    }

    private CompletableFuture<JenkinsClient> connect() {
        String accountId = myOptions.accountId;
        Account account = accountId == null ? null : myAccountManager.findAccount(accountId, myProject);
        if (account == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(JenkinsLocalize.errorNoAccount(getName()).get()));
        }
        return myAccountManager.connect(account).thenApply(connection -> JenkinsClient.create(connection, myOptions.serverUrl));
    }

    @Override
    public CompletableFuture<?> testConnection() {
        return client()
            .thenCompose(client -> client.getJson(JenkinsUrls.api(client.getRootUrl(), JenkinsJson.SERVER_TREE)))
            .thenApply(server -> {
                if (!JenkinsJson.isServer(server)) {
                    throw new IllegalStateException(JenkinsLocalize.errorNotJenkins(getName()).get());
                }
                return server;
            });
    }

    @Override
    public CompletableFuture<List<CIJob>> loadJobs(@Nullable CIJob parent) {
        return client()
            .thenCompose(client -> {
                String url = parent == null ? client.getServerUrl() : parent.id();
                return client.getJson(JenkinsUrls.api(url, JenkinsJson.JOBS_TREE));
            })
            .thenApply(JenkinsJson::jobs);
    }

    @Override
    public CompletableFuture<CIJob> loadJob(CIJob job) {
        return client()
            .thenCompose(client -> client.getJson(JenkinsUrls.api(job.id(), JenkinsJson.JOB_TREE)))
            .thenApply(JenkinsJson::job);
    }

    @Override
    public CompletableFuture<List<CIBuild>> loadBuilds(CIJob job, int limit) {
        return client()
            .thenCompose(client -> client.getJson(JenkinsUrls.api(job.id(), JenkinsJson.buildsTree(limit))))
            .thenApply(json -> JenkinsJson.builds(json, job.id()));
    }

    @Override
    public CompletableFuture<CIBuild> loadBuild(CIBuild build) {
        String queueItemUrl = JenkinsUrls.queueItemUrlOf(build.id());
        if (queueItemUrl != null) {
            return client().thenCompose(client -> loadQueuedBuild(client, build, queueItemUrl));
        }
        return client().thenCompose(client -> loadBuild(client, build.id(), build.jobId()));
    }

    private static CompletableFuture<CIBuild> loadBuild(JenkinsClient client, String buildUrl, String jobId) {
        return client.getJson(JenkinsUrls.api(buildUrl, JenkinsJson.BUILD_TREE)).thenApply(json -> JenkinsJson.build(json, jobId));
    }

    /**
     * A queue item is waiting, became a build, or was cancelled. Jenkins forgets queue items a few minutes after they
     * left the queue, then the build is found by its {@code queueId}.
     */
    private static CompletableFuture<CIBuild> loadQueuedBuild(JenkinsClient client, CIBuild build, String queueItemUrl) {
        long queueId = JenkinsUrls.queueId(queueItemUrl);
        return client.getJson(JenkinsUrls.api(queueItemUrl, JenkinsJson.QUEUE_ITEM_TREE))
            .thenCompose(json -> {
                JenkinsJson.QueueItem item = JenkinsJson.queueItem(json);
                String executableUrl = item.executableUrl();
                if (executableUrl != null) {
                    return loadBuild(client, executableUrl, build.jobId());
                }
                CIBuildStatus status = item.cancelled() ? CIBuildStatus.ABORTED : CIBuildStatus.QUEUED;
                return CompletableFuture.completedFuture(build.toBuilder().status(status).description(item.why()).build());
            })
            .exceptionallyCompose(error -> {
                if (queueId < 0 || !JenkinsClient.isStatus(error, 404)) {
                    return CompletableFuture.failedFuture(error);
                }
                String tree = "builds[" + JenkinsJson.BUILD_TREE + "]{0," + QUEUE_LOOKUP_BUILDS + "}";
                return client.getJson(JenkinsUrls.api(build.jobId(), tree)).thenApply(json -> {
                    CIBuild started = JenkinsJson.buildByQueueId(json, build.jobId(), queueId);
                    return started != null ? started : build.toBuilder().status(CIBuildStatus.UNKNOWN).build();
                });
            });
    }

    @Override
    public CompletableFuture<List<CIParameterDefinition>> loadParameters(CIJob job) {
        return client()
            .thenCompose(client -> client.getJson(JenkinsUrls.api(job.id(), JenkinsJson.PARAMETERS_TREE)))
            .thenApply(JenkinsJson::parameters);
    }

    @Override
    public CompletableFuture<CIBuild> runBuild(CIJob job, CIRunRequest request) {
        return client().thenCompose(client -> startBuild(client, job, request).thenApply(response -> {
            long queueId = JenkinsUrls.queueId(JenkinsClient.header(response, "Location"));
            if (queueId < 0) {
                throw new IllegalStateException(JenkinsLocalize.errorNoQueueItem(job.displayName()).get());
            }
            String queueItemUrl = JenkinsUrls.queueItemUrl(client.getRootUrl(), queueId);
            return CIBuild.builder(JenkinsUrls.queueBuildId(queueItemUrl), job.id())
                .webUrl(job.webUrl())
                .status(CIBuildStatus.QUEUED)
                .build();
        }));
    }

    /**
     * Jobs without parameters are started with {@code build}, jobs with parameters with {@code buildWithParameters}
     * (missing values take their defaults). File parameters need the multipart form of the job page: a {@code json}
     * field {@code {"parameter":[{"name":..,"value":..},{"name":..,"file":"file0"}]}} and a part per file.
     */
    private static CompletableFuture<HttpApiResponse<String>> startBuild(JenkinsClient client, CIJob job, CIRunRequest request) {
        String jobUrl = JenkinsUrls.withSlash(job.id());
        Map<String, CIParameterValue> parameters = request.parameters();
        if (parameters.isEmpty() && !job.hasParameters()) {
            return client.post(jobUrl + "build?delay=0sec");
        }

        boolean hasFiles = false;
        for (CIParameterValue value : parameters.values()) {
            if (value instanceof CIParameterValue.File) {
                hasFiles = true;
                break;
            }
        }

        if (!hasFiles) {
            Map<String, String> form = new LinkedHashMap<>();
            for (Map.Entry<String, CIParameterValue> entry : parameters.entrySet()) {
                if (entry.getValue() instanceof CIParameterValue.Text text) {
                    form.put(entry.getKey(), text.value());
                }
            }
            return client.postForm(jobUrl + "buildWithParameters?delay=0sec", form);
        }

        JsonArray parameterArray = new JsonArray();
        Map<String, Path> files = new LinkedHashMap<>();
        for (Map.Entry<String, CIParameterValue> entry : parameters.entrySet()) {
            JsonObject parameter = new JsonObject();
            parameter.addProperty("name", entry.getKey());
            switch (entry.getValue()) {
                case CIParameterValue.Text text -> parameter.addProperty("value", text.value());
                case CIParameterValue.File file -> {
                    String partName = "file" + files.size();
                    parameter.addProperty("file", partName);
                    files.put(partName, file.file());
                }
            }
            parameterArray.add(parameter);
        }
        JsonObject json = new JsonObject();
        json.add("parameter", parameterArray);
        return client.postMultipart(jobUrl + "build?delay=0sec", json.toString(), files);
    }

    @Override
    public CompletableFuture<?> stopBuild(CIBuild build) {
        String queueItemUrl = JenkinsUrls.queueItemUrlOf(build.id());
        if (queueItemUrl == null) {
            return client().thenCompose(client -> client.post(JenkinsUrls.withSlash(build.id()) + "stop"));
        }

        return client().thenCompose(client -> cancelQueueItem(client, build, queueItemUrl));
    }

    private static CompletableFuture<Object> cancelQueueItem(JenkinsClient client, CIBuild build, String queueItemUrl) {
        long queueId = JenkinsUrls.queueId(queueItemUrl);
        CompletableFuture<Object> cancel =
            client.post(client.getRootUrl() + "queue/cancelItem?id=" + queueId).thenApply(response -> response);
        return cancel.exceptionallyCompose(error -> {
            if (!JenkinsClient.isStatus(error, 404)) {
                return CompletableFuture.failedFuture(error);
            }
            // the item left the queue meanwhile, stop the build it became
            return loadQueuedBuild(client, build, queueItemUrl).thenCompose(started -> {
                if (JenkinsUrls.queueItemUrlOf(started.id()) != null || !started.status().isActive()) {
                    return CompletableFuture.<Object>completedFuture(started);
                }
                return client.post(JenkinsUrls.withSlash(started.id()) + "stop").<Object>thenApply(response -> response);
            });
        });
    }

    /**
     * Pipeline stages have no logs of their own here ({@link CIBuildStage#hasLog()} is {@code false}), so the log is
     * always the one of the whole build.
     */
    @Override
    public CompletableFuture<CILogChunk> loadLog(CIBuild build, @Nullable CIBuildStage stage, long offset) {
        if (JenkinsUrls.queueItemUrlOf(build.id()) != null) {
            return CompletableFuture.completedFuture(new CILogChunk("", offset, false));
        }
        String url = JenkinsUrls.withSlash(build.id()) + "logText/progressiveText?start=" + Math.max(0, offset);
        return client()
            .thenCompose(client -> client.getText(url))
            .thenApply(response -> JenkinsJson.logChunk(
                response.body(),
                offset,
                JenkinsClient.header(response, "X-Text-Size"),
                JenkinsClient.header(response, "X-More-Data")
            ));
    }

    @Override
    public CompletableFuture<List<CIView>> loadViews() {
        return client()
            .thenCompose(client -> client.getJson(JenkinsUrls.api(client.getRootUrl(), JenkinsJson.VIEWS_TREE)))
            .thenApply(JenkinsJson::views);
    }

    @Override
    public CompletableFuture<List<CIJob>> loadJobs(CIView view) {
        return client()
            .thenCompose(client -> client.getJson(JenkinsUrls.api(view.id(), JenkinsJson.JOBS_TREE)))
            .thenApply(JenkinsJson::jobs);
    }

    /**
     * Stages of pipeline builds from the Pipeline Stage View plugin; other builds, and servers without the plugin, have
     * none.
     */
    @Override
    public CompletableFuture<List<CIBuildStage>> loadStages(CIBuild build) {
        if (JenkinsUrls.queueItemUrlOf(build.id()) != null) {
            return CompletableFuture.completedFuture(List.of());
        }
        return client()
            .thenCompose(client -> client.getJson(JenkinsUrls.withSlash(build.id()) + "wfapi/describe"))
            .thenApply(JenkinsJson::stages)
            .exceptionallyCompose(error -> JenkinsClient.isStatus(error, 404)
                ? CompletableFuture.completedFuture(List.of())
                : CompletableFuture.failedFuture(error));
    }

    @Override
    public CompletableFuture<List<CIArtifact>> loadArtifacts(CIBuild build) {
        if (JenkinsUrls.queueItemUrlOf(build.id()) != null) {
            return CompletableFuture.completedFuture(List.of());
        }
        String buildUrl = build.webUrl() != null ? build.webUrl() : build.id();
        return client()
            .thenCompose(client -> client.getJson(JenkinsUrls.api(build.id(), JenkinsJson.ARTIFACTS_TREE)))
            .thenApply(json -> JenkinsJson.artifacts(json, buildUrl));
    }

    @Override
    public CompletableFuture<?> download(CIBuild build, CIArtifact artifact, Path target) {
        return client().thenCompose(client ->
            client.download(JenkinsUrls.withSlash(build.id()) + "artifact/" + JenkinsUrls.encodePath(artifact.id()), target));
    }

    /**
     * Starts the job again with the parameter values of the build. Jenkins does not export password and file values,
     * those take their defaults.
     */
    @Override
    public CompletableFuture<CIBuild> rerun(CIBuild build, boolean failedOnly) {
        CIJob jobRef = CIJob.builder(build.jobId(), "").build();
        if (JenkinsUrls.queueItemUrlOf(build.id()) != null) {
            return loadJob(jobRef).thenCompose(job -> runBuild(job, CIRunRequest.DEFAULT));
        }
        return client()
            .thenCompose(client -> client.getJson(JenkinsUrls.api(build.id(), JenkinsJson.BUILD_PARAMETERS_TREE)))
            .thenApply(JenkinsJson::buildParameterValues)
            .thenCompose(values -> loadJob(jobRef).thenCompose(job -> {
                Map<String, CIParameterValue> parameters = new LinkedHashMap<>();
                values.forEach((name, value) -> parameters.put(name, CIParameterValue.text(value)));
                return runBuild(job, new CIRunRequest(null, parameters));
            }));
    }
}
