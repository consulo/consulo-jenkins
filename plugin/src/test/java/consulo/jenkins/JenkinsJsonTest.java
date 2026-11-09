/*
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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import consulo.continuousIntegration.CIBuild;
import consulo.continuousIntegration.CIBuildStage;
import consulo.continuousIntegration.CIBuildStatus;
import consulo.continuousIntegration.CIJob;
import consulo.continuousIntegration.CIJobKind;
import consulo.continuousIntegration.CILogChunk;
import consulo.continuousIntegration.CIParameterDefinition;
import consulo.continuousIntegration.CIParameterKind;
import consulo.continuousIntegration.support.CIArtifact;
import consulo.continuousIntegration.support.CIView;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses answers of https://ci.consulo.io (Jenkins 2.576, anonymous read access), captured with the queries of
 * {@link JenkinsJson}. The {@code synthetic-*.json} answers cover what that server does not have (folders, pipelines,
 * parameters, queue items) and follow the format of the Jenkins remote access API.
 *
 * @author VISTALL
 * @since 2026-11-09
 */
public class JenkinsJsonTest {
    @Test
    public void testServer() {
        assertTrue(JenkinsJson.isServer(load("server.json")));
        assertFalse(JenkinsJson.isServer(load("whoami-anonymous.json")));
        assertTrue(JenkinsJson.bool(load("whoami-anonymous.json"), "anonymous"));
    }

    @Test
    public void testViews() {
        List<CIView> views = JenkinsJson.views(load("server-views.json"));
        print("views", views);
        assertEquals(9, views.size());
        CIView all = views.get(0);
        assertEquals("all", all.name());
        assertTrue(all.primary());
        assertEquals("https://ci.consulo.io/", all.id());
        assertEquals(1, views.stream().filter(CIView::primary).count());
    }

    @Test
    public void testRootJobs() {
        List<CIJob> jobs = JenkinsJson.jobs(load("jobs-root.json"));
        print("root jobs", jobs);
        assertEquals(8, jobs.size());

        CIJob android = jobs.get(0);
        assertEquals("https://ci.consulo.io/job/android/", android.id());
        assertEquals("android", android.name());
        assertEquals(CIJobKind.JOB, android.kind());
        assertEquals(CIBuildStatus.SUCCESS, android.status());
        assertTrue(android.buildable());
        assertEquals(100, android.healthScore());
        assertFalse(android.hasParameters());
        CIBuild lastBuild = android.lastBuild();
        assertNotNull(lastBuild);
        assertEquals("711", lastBuild.number());
        assertEquals("#711", lastBuild.displayName());
        assertEquals(android.id(), lastBuild.jobId());
    }

    @Test
    public void testViewJobs() {
        List<CIJob> jobs = JenkinsJson.jobs(load("jobs-view-consulo-internal.json"));
        print("view consulo.internal", jobs);
        assertEquals(53, jobs.size());

        for (CIJob job : jobs) {
            assertEquals(CIJobKind.JOB, job.kind(), job.name());
        }
        assertTrue(jobs.stream().anyMatch(job -> job.status() == CIBuildStatus.UNSTABLE));
        assertTrue(jobs.stream().anyMatch(job -> job.status() == CIBuildStatus.FAILURE));

        // disabled jobs: not buildable, status of the last build
        List<CIJob> disabled = jobs.stream().filter(job -> !job.buildable()).toList();
        assertEquals(3, disabled.size());
        for (CIJob job : disabled) {
            CIBuild lastBuild = job.lastBuild();
            assertEquals(lastBuild == null ? CIBuildStatus.UNKNOWN : lastBuild.status(), job.status(), job.name());
        }
    }

    @Test
    public void testJob() {
        CIJob job = JenkinsJson.job(load("job-consulo.json"));
        print("job", List.of(job));
        assertEquals("https://ci.consulo.io/job/consulo/", job.id());
        assertEquals(CIJobKind.JOB, job.kind());
        assertNotNull(job.lastBuild());
    }

    @Test
    public void testBuilds() {
        String jobId = "https://ci.consulo.io/job/consulo/";
        JsonObject json = load("builds-consulo.json");
        List<CIBuild> builds = JenkinsJson.builds(json, jobId);
        print("builds", builds);
        assertEquals(5, builds.size());
        for (int i = 1; i < builds.size(); i++) {
            assertTrue(Long.parseLong(builds.get(i - 1).number()) > Long.parseLong(builds.get(i).number()), "newest first");
        }

        CIBuild newest = builds.get(0);
        assertEquals(jobId, newest.jobId());
        assertEquals(newest.webUrl(), newest.id());
        assertTrue(newest.status().isFinished());
        assertEquals("master", newest.branch());
        String revision = newest.revision();
        assertNotNull(revision);
        assertEquals(40, revision.length());
        assertNotNull(newest.startTime());
        Duration duration = newest.duration();
        assertNotNull(duration);
        assertTrue(duration.compareTo(Duration.ZERO) > 0);

        long queueId = JenkinsJson.number(JenkinsJson.objects(json, "builds").get(2), "queueId", -1);
        assertTrue(queueId > 0);
        CIBuild byQueue = JenkinsJson.buildByQueueId(json, jobId, queueId);
        assertNotNull(byQueue);
        assertEquals(builds.get(2), byQueue);
        assertNull(JenkinsJson.buildByQueueId(json, jobId, Long.MAX_VALUE));
    }

    @Test
    public void testBuild() {
        CIBuild build = JenkinsJson.build(load("build-consulo-4644.json"), "https://ci.consulo.io/job/consulo/");
        print("build", List.of(build));
        assertEquals("https://ci.consulo.io/job/consulo/4644/", build.id());
        assertEquals("4644", build.number());
        assertEquals(CIBuildStatus.SUCCESS, build.status());
        assertEquals("master", build.branch());

        assertEquals(Map.of(), JenkinsJson.buildParameterValues(load("build-parameters-consulo-4644.json")));
    }

    @Test
    public void testArtifacts() {
        List<CIArtifact> artifacts = JenkinsJson.artifacts(load("artifacts-hub.json"), "https://ci.consulo.io/job/hub.consulo.io/2586/");
        print("artifacts", artifacts);
        assertFalse(artifacts.isEmpty());
        for (CIArtifact artifact : artifacts) {
            assertEquals(-1, artifact.size());
            assertEquals("https://ci.consulo.io/job/hub.consulo.io/2586/artifact/" + artifact.id(), artifact.webUrl());
        }
    }

    @Test
    public void testParameters() {
        assertEquals(List.of(), JenkinsJson.parameters(load("parameters-android.json")));

        List<CIParameterDefinition> parameters = JenkinsJson.parameters(load("synthetic-parameters.json"));
        print("parameters", parameters);
        assertEquals(8, parameters.size());

        assertParameter(parameters.get(0), "BRANCH", CIParameterKind.STRING, "master");
        assertEquals("Branch to build", parameters.get(0).description());
        assertParameter(parameters.get(1), "NOTES", CIParameterKind.TEXT, "line one\nline two");
        assertNull(parameters.get(1).description());
        assertParameter(parameters.get(2), "RUN_TESTS", CIParameterKind.BOOLEAN, "true");
        assertParameter(parameters.get(3), "PLATFORM", CIParameterKind.CHOICE, "linux");
        assertEquals(List.of("linux", "windows", "mac"), parameters.get(3).choices());
        assertParameter(parameters.get(4), "DEPLOY_PASSWORD", CIParameterKind.PASSWORD, null);
        assertParameter(parameters.get(5), "patch.diff", CIParameterKind.FILE, null);

        CIParameterDefinition git = parameters.get(6);
        assertParameter(git, "GIT_BRANCH", CIParameterKind.OTHER, "origin/master");
        assertEquals("net.uaznia.lukanus.hudson.plugins.gitparameter.GitParameterDefinition", git.typeId());
        assertEquals("PT_BRANCH", git.attribute(JenkinsJson.ATTRIBUTE_TYPE));
        assertParameter(parameters.get(7), "NODE", CIParameterKind.OTHER, null);

        assertEquals(CIParameterKind.CHOICE, JenkinsJson.parameterKind(null, "ChoiceParameterDefinition"));
    }

    @Test
    public void testBuildParameterValues() {
        Map<String, String> values = JenkinsJson.buildParameterValues(load("synthetic-build-parameters.json"));
        assertEquals(Map.of("BRANCH", "release/3.0", "RUN_TESTS", "false"), values);
    }

    @Test
    public void testFolders() {
        List<CIJob> jobs = JenkinsJson.jobs(load("synthetic-jobs-folders.json"));
        print("folders", jobs);
        assertEquals(7, jobs.size());

        assertEquals(CIJobKind.FOLDER, jobs.get(0).kind());
        assertEquals("Libraries", jobs.get(0).displayName());
        assertFalse(jobs.get(0).buildable());
        assertEquals(CIBuildStatus.UNKNOWN, jobs.get(0).status());
        assertEquals(CIJobKind.BRANCH_FOLDER, jobs.get(1).kind());
        assertEquals(CIJobKind.FOLDER, jobs.get(2).kind());
        assertEquals(CIJobKind.FOLDER, jobs.get(3).kind(), "unknown class with jobs");

        CIJob nightly = jobs.get(4);
        assertEquals(CIJobKind.JOB, nightly.kind());
        assertEquals(CIBuildStatus.RUNNING, nightly.status());
        assertTrue(nightly.hasParameters());
        CIBuild running = nightly.lastBuild();
        assertNotNull(running);
        assertEquals(CIBuildStatus.RUNNING, running.status());
        assertNull(running.duration());
        assertEquals(Duration.ofMillis(612345), running.estimatedDuration());

        assertEquals(CIBuildStatus.NOT_BUILT, jobs.get(5).status());
        assertTrue(jobs.get(5).queued());
        assertNull(jobs.get(5).lastBuild());
        assertEquals(CIBuildStatus.ABORTED, jobs.get(6).status());
    }

    @Test
    public void testQueueItems() {
        JenkinsJson.QueueItem waiting = JenkinsJson.queueItem(load("synthetic-queue-item-waiting.json"));
        assertEquals(66601, waiting.id());
        assertFalse(waiting.cancelled());
        assertNull(waiting.executableUrl());
        assertEquals("In the quiet period. Expires in 4.9 sec", waiting.why());

        JenkinsJson.QueueItem left = JenkinsJson.queueItem(load("synthetic-queue-item-left.json"));
        assertEquals("https://ci.consulo.io/job/android/712/", left.executableUrl());
        assertNull(left.why());

        JenkinsJson.QueueItem cancelled = JenkinsJson.queueItem(load("synthetic-queue-item-cancelled.json"));
        assertTrue(cancelled.cancelled());
        assertNull(cancelled.executableUrl());
    }

    @Test
    public void testStages() {
        List<CIBuildStage> stages = JenkinsJson.stages(load("synthetic-wfapi-describe.json"));
        print("stages", stages);
        assertEquals(4, stages.size());
        assertEquals(CIBuildStatus.SUCCESS, stages.get(0).status());
        assertEquals(CIBuildStatus.NOT_BUILT, stages.get(1).status());
        assertEquals(CIBuildStatus.RUNNING, stages.get(3).status());
        assertEquals("Test", stages.get(3).name());
        assertFalse(stages.get(3).hasLog());
    }

    @Test
    public void testLog() throws IOException {
        String text = text("log-android-711.txt");
        // headers of the answer: X-Text-Size: 1098, no X-More-Data
        CILogChunk finished = JenkinsJson.logChunk(text, 0, "1098", null);
        assertTrue(finished.complete());
        assertEquals(1098, finished.nextOffset());
        assertTrue(finished.text().startsWith("Started by an SCM change"));

        CILogChunk running = JenkinsJson.logChunk("", 1098, "1098", "true");
        assertFalse(running.complete());
        assertEquals(1098, running.nextOffset());

        assertEquals(5, JenkinsJson.logChunk("hello", 0, null, "true").nextOffset());
    }

    @Test
    public void testBranchNames() {
        assertEquals("master", JenkinsJson.branchName("refs/remotes/origin/master"));
        assertEquals("feature/x", JenkinsJson.branchName("refs/remotes/upstream/feature/x"));
        assertEquals("master", JenkinsJson.branchName("origin/master"));
        assertEquals("main", JenkinsJson.branchName("refs/heads/main"));
        assertEquals("PR-12", JenkinsJson.branchName("PR-12"));
        assertNull(JenkinsJson.branchName(""));
    }

    @Test
    public void testUrls() {
        String server = JenkinsUrls.normalizeServerUrl(" https://ci.consulo.io ");
        assertEquals("https://ci.consulo.io/", server);
        assertEquals(
            "https://ci.consulo.io/job/x/api/json?tree=jobs%5Bname%5D%7B0%2C0%7D",
            JenkinsUrls.api(server + "job/x", "jobs[name]{0,0}")
        );

        assertEquals(66601, JenkinsUrls.queueId("https://ci.consulo.io/queue/item/66601/"));
        assertEquals(66601, JenkinsUrls.queueId("http://internal:8080/jenkins/queue/item/66601"));
        assertEquals(-1, JenkinsUrls.queueId("https://ci.consulo.io/job/android/"));
        assertEquals(-1, JenkinsUrls.queueId(null));

        String queueItemUrl = JenkinsUrls.queueItemUrl(server, 66601);
        String buildId = JenkinsUrls.queueBuildId(queueItemUrl);
        assertEquals("queue:https://ci.consulo.io/queue/item/66601/", buildId);
        assertEquals(queueItemUrl, JenkinsUrls.queueItemUrlOf(buildId));
        assertNull(JenkinsUrls.queueItemUrlOf("https://ci.consulo.io/job/android/711/"));

        String proxy = "https://proxy.example.com/ci/";
        assertEquals("https://ci.consulo.io/job/a/1/", JenkinsUrls.rebase("https://ci.consulo.io/job/a/1/", server));
        assertEquals(proxy + "job/a/job/b/", JenkinsUrls.rebase("http://jenkins:8080/job/a/job/b/", proxy));
        assertEquals(proxy + "queue/item/5/", JenkinsUrls.rebase("/jenkins/queue/item/5/", proxy));

        assertEquals(server, JenkinsUrls.rootUrl(server));
        assertEquals(server, JenkinsUrls.rootUrl("https://ci.consulo.io/view/consulo.internal/"));
        assertEquals("http://build.local:8080/jenkins/", JenkinsUrls.rootUrl("http://build.local:8080/jenkins/job/folder/job/app"));

        assertEquals("ci.consulo.io", JenkinsUrls.presentableName(server));
        assertEquals("build.local:8080/jenkins", JenkinsUrls.presentableName("http://build.local:8080/jenkins/"));
        assertEquals("target/my%20app%2B1.jar", JenkinsUrls.encodePath("target/my app+1.jar"));
    }

    private static void assertParameter(CIParameterDefinition parameter, String name, CIParameterKind kind, @Nullable String defaultValue) {
        assertEquals(name, parameter.name());
        assertEquals(kind, parameter.kind(), name);
        assertEquals(defaultValue, parameter.defaultValue(), name);
    }

    private static void print(String title, List<?> values) {
        System.out.println("== " + title + " (" + values.size() + ")");
        for (Object value : values) {
            System.out.println("  " + value);
        }
    }

    private static JsonObject load(String name) {
        try {
            return JsonParser.parseString(text(name)).getAsJsonObject();
        }
        catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(String name) throws IOException {
        try (InputStream stream = JenkinsJsonTest.class.getResourceAsStream(name)) {
            if (stream == null) {
                throw new IOException("Missing fixture " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
