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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code tree} queries of the Jenkins remote access API and the mapping of their answers to the continuous
 * integration model. Pure functions over Gson objects, without platform services.
 *
 * @author VISTALL
 * @since 2026-11-09
 */
public final class JenkinsJson {
    /**
     * {@link CIParameterDefinition#attributes()} key with the {@code type} Jenkins reports for a parameter.
     */
    public static final String ATTRIBUTE_TYPE = "type";

    public static final String LAST_BUILD_TREE = "number,url,result,building,timestamp,duration,estimatedDuration,displayName";

    public static final String BUILD_TREE = LAST_BUILD_TREE + ",description,queueId,actions[lastBuiltRevision[SHA1,branch[name]]]";

    /**
     * {@code jobs[name]{0,0}} answers an empty array for every kind of folder and nothing for jobs, so folders of
     * unknown classes are recognized without loading their children.
     */
    public static final String JOB_TREE = "_class,name,displayName,fullName,url,color,buildable,inQueue,description,healthReport[score],"
        + "lastBuild[" + LAST_BUILD_TREE + "],property[parameterDefinitions[name]],jobs[name]{0,0}";

    public static final String JOBS_TREE = "jobs[" + JOB_TREE + "]";

    public static final String PARAMETERS_TREE =
        "property[parameterDefinitions[name,description,type,_class,defaultParameterValue[value],choices]]";

    public static final String VIEWS_TREE = "views[name,url,_class],primaryView[name]";

    public static final String QUEUE_ITEM_TREE = "_class,id,why,blocked,buildable,stuck,cancelled,executable[number,url]";

    public static final String ARTIFACTS_TREE = "artifacts[relativePath,fileName,displayPath]";

    public static final String BUILD_PARAMETERS_TREE = "actions[parameters[_class,name,value]]";

    public static final String SERVER_TREE = "mode,url";

    public static final String WHO_AM_I_TREE = "name,anonymous,authenticated";

    public static final String USER_TREE = "id,fullName";

    /**
     * State of a queue item: waiting, left the queue as a build, or cancelled.
     *
     * @param executableUrl URL of the build once the item left the queue
     */
    public record QueueItem(long id, @Nullable String why, boolean cancelled, @Nullable String executableUrl) {
    }

    private JenkinsJson() {
    }

    public static String buildsTree(int limit) {
        return "builds[" + BUILD_TREE + "]{0," + Math.max(limit, 1) + "}";
    }

    public static boolean isServer(JsonObject object) {
        return object.has("mode") || "hudson.model.Hudson".equals(string(object, "_class"));
    }

    public static List<CIJob> jobs(JsonObject container) {
        List<CIJob> result = new ArrayList<>();
        for (JsonObject job : objects(container, "jobs")) {
            result.add(job(job));
        }
        return result;
    }

    public static CIJob job(JsonObject object) {
        String name = notNull(string(object, "name"));
        String url = string(object, "url");
        String id = url != null ? url : name;

        JsonObject lastBuildObject = object(object, "lastBuild");
        CIBuild lastBuild = lastBuildObject == null ? null : build(lastBuildObject, id);

        CIBuildStatus status = colorStatus(string(object, "color"));
        if (status == CIBuildStatus.UNKNOWN && lastBuild != null) {
            // disabled jobs keep the result of their last build
            status = lastBuild.status();
        }

        return CIJob.builder(id, name)
            .displayName(notNull(string(object, "displayName")))
            .fullName(notNull(string(object, "fullName")))
            .webUrl(url)
            .kind(kind(string(object, "_class"), object.has("jobs")))
            .buildable(bool(object, "buildable"))
            .status(status)
            .queued(bool(object, "inQueue"))
            .healthScore(healthScore(object))
            .description(emptyToNull(string(object, "description")))
            .hasParameters(hasParameters(object))
            .lastBuild(lastBuild)
            .build();
    }

    public static CIJobKind kind(@Nullable String jenkinsClass, boolean hasJobs) {
        String className = jenkinsClass == null ? "" : jenkinsClass;
        if (className.endsWith("MultiBranchProject")) {
            return CIJobKind.BRANCH_FOLDER;
        }
        if (className.endsWith("Folder")) {
            // com.cloudbees.hudson.plugins.folder.Folder, jenkins.branch.OrganizationFolder, computed folders
            return CIJobKind.FOLDER;
        }
        return hasJobs ? CIJobKind.FOLDER : CIJobKind.JOB;
    }

    /**
     * Status of a job from its ball color, see {@code hudson.model.BallColor}.
     */
    public static CIBuildStatus colorStatus(@Nullable String color) {
        if (color == null || color.isEmpty()) {
            return CIBuildStatus.UNKNOWN;
        }
        String value = color.toLowerCase(Locale.ROOT);
        if (value.endsWith("_anime")) {
            return CIBuildStatus.RUNNING;
        }
        return switch (value) {
            case "blue", "green" -> CIBuildStatus.SUCCESS;
            case "yellow" -> CIBuildStatus.UNSTABLE;
            case "red" -> CIBuildStatus.FAILURE;
            case "aborted" -> CIBuildStatus.ABORTED;
            case "notbuilt", "nobuilt", "grey" -> CIBuildStatus.NOT_BUILT;
            default -> CIBuildStatus.UNKNOWN;
        };
    }

    public static CIBuildStatus resultStatus(@Nullable String result, boolean building) {
        if (building) {
            return CIBuildStatus.RUNNING;
        }
        if (result == null) {
            return CIBuildStatus.UNKNOWN;
        }
        return switch (result.toUpperCase(Locale.ROOT)) {
            case "SUCCESS" -> CIBuildStatus.SUCCESS;
            case "UNSTABLE" -> CIBuildStatus.UNSTABLE;
            case "FAILURE" -> CIBuildStatus.FAILURE;
            case "ABORTED" -> CIBuildStatus.ABORTED;
            case "NOT_BUILT" -> CIBuildStatus.NOT_BUILT;
            default -> CIBuildStatus.UNKNOWN;
        };
    }

    public static int healthScore(JsonObject job) {
        List<JsonObject> reports = objects(job, "healthReport");
        if (reports.isEmpty()) {
            return -1;
        }
        return (int) number(reports.get(0), "score", -1);
    }

    public static boolean hasParameters(JsonObject job) {
        for (JsonObject property : objects(job, "property")) {
            if (!objects(property, "parameterDefinitions").isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static List<CIBuild> builds(JsonObject job, String jobId) {
        List<CIBuild> result = new ArrayList<>();
        for (JsonObject build : objects(job, "builds")) {
            result.add(build(build, jobId));
        }
        return result;
    }

    public static CIBuild build(JsonObject object, String jobId) {
        long number = number(object, "number", -1);
        String numberText = number >= 0 ? Long.toString(number) : "";
        String url = string(object, "url");
        String id = url != null ? url : JenkinsUrls.withSlash(jobId) + numberText + "/";

        boolean building = bool(object, "building");
        long timestamp = number(object, "timestamp", 0);
        long estimatedDuration = number(object, "estimatedDuration", -1);

        Duration duration = null;
        if (!building && object.has("duration")) {
            duration = Duration.ofMillis(Math.max(0, number(object, "duration", 0)));
        }

        String revision = null;
        String branch = null;
        for (JsonObject action : objects(object, "actions")) {
            JsonObject lastBuiltRevision = object(action, "lastBuiltRevision");
            if (lastBuiltRevision != null) {
                revision = string(lastBuiltRevision, "SHA1");
                List<JsonObject> branches = objects(lastBuiltRevision, "branch");
                branch = branches.isEmpty() ? null : branchName(string(branches.get(0), "name"));
                break;
            }
        }

        return CIBuild.builder(id, jobId)
            .number(numberText)
            .displayName(notNull(string(object, "displayName")))
            .webUrl(url)
            .status(resultStatus(string(object, "result"), building))
            .startTime(timestamp > 0 ? Instant.ofEpochMilli(timestamp) : null)
            .duration(duration)
            .estimatedDuration(estimatedDuration > 0 ? Duration.ofMillis(estimatedDuration) : null)
            .branch(branch)
            .revision(revision)
            .description(emptyToNull(string(object, "description")))
            .build();
    }

    /**
     * The build that a queue item became, by the {@code queueId} of the builds of its job. Used once Jenkins forgot
     * the queue item (a few minutes after it left the queue).
     */
    public static @Nullable CIBuild buildByQueueId(JsonObject job, String jobId, long queueId) {
        for (JsonObject build : objects(job, "builds")) {
            if (number(build, "queueId", -1) == queueId) {
                return build(build, jobId);
            }
        }
        return null;
    }

    /**
     * Branch name from a git plugin ref: {@code refs/remotes/origin/master} and {@code origin/master} are
     * {@code master}.
     */
    public static @Nullable String branchName(@Nullable String ref) {
        if (ref == null || ref.isEmpty()) {
            return null;
        }
        if (ref.startsWith("refs/remotes/")) {
            String remoteAndBranch = ref.substring("refs/remotes/".length());
            int slash = remoteAndBranch.indexOf('/');
            return slash < 0 ? remoteAndBranch : remoteAndBranch.substring(slash + 1);
        }
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        if (ref.startsWith("origin/")) {
            return ref.substring("origin/".length());
        }
        return ref;
    }

    public static List<CIParameterDefinition> parameters(JsonObject job) {
        List<CIParameterDefinition> result = new ArrayList<>();
        for (JsonObject property : objects(job, "property")) {
            for (JsonObject definition : objects(property, "parameterDefinitions")) {
                result.add(parameter(definition));
            }
        }
        return result;
    }

    public static CIParameterDefinition parameter(JsonObject definition) {
        String type = string(definition, "type");
        String jenkinsClass = string(definition, "_class");
        String typeId = jenkinsClass != null ? jenkinsClass : notNull(type);

        Map<String, String> attributes = new LinkedHashMap<>();
        if (type != null) {
            attributes.put(ATTRIBUTE_TYPE, type);
        }

        return new CIParameterDefinition(
            notNull(string(definition, "name")),
            emptyToNull(string(definition, "description")),
            parameterKind(jenkinsClass, type),
            typeId,
            string(object(definition, "defaultParameterValue"), "value"),
            strings(definition, "choices"),
            false,
            attributes
        );
    }

    /**
     * Kind of the parameter types of Jenkins core; the types of Jenkins plugins are {@link CIParameterKind#OTHER} with
     * their class as {@link CIParameterDefinition#typeId()}.
     */
    public static CIParameterKind parameterKind(@Nullable String jenkinsClass, @Nullable String type) {
        String className = jenkinsClass != null ? jenkinsClass : type == null ? "" : "hudson.model." + type;
        return switch (className) {
            case "hudson.model.StringParameterDefinition" -> CIParameterKind.STRING;
            case "hudson.model.TextParameterDefinition" -> CIParameterKind.TEXT;
            case "hudson.model.BooleanParameterDefinition" -> CIParameterKind.BOOLEAN;
            case "hudson.model.ChoiceParameterDefinition" -> CIParameterKind.CHOICE;
            case "hudson.model.PasswordParameterDefinition" -> CIParameterKind.PASSWORD;
            case "hudson.model.FileParameterDefinition" -> CIParameterKind.FILE;
            default -> CIParameterKind.OTHER;
        };
    }

    /**
     * Values of the parameters a build was started with. Values Jenkins does not export (passwords, files) are left
     * out, so the job uses its defaults for them.
     */
    public static Map<String, String> buildParameterValues(JsonObject build) {
        Map<String, String> result = new LinkedHashMap<>();
        for (JsonObject action : objects(build, "actions")) {
            for (JsonObject parameter : objects(action, "parameters")) {
                String name = string(parameter, "name");
                String value = string(parameter, "value");
                if (name != null && value != null) {
                    result.put(name, value);
                }
            }
        }
        return result;
    }

    public static List<CIView> views(JsonObject server) {
        String primaryName = string(object(server, "primaryView"), "name");
        List<CIView> result = new ArrayList<>();
        for (JsonObject view : objects(server, "views")) {
            String url = string(view, "url");
            String name = string(view, "name");
            if (url == null || name == null) {
                continue;
            }
            result.add(new CIView(url, name, url, name.equals(primaryName)));
        }
        return result;
    }

    public static QueueItem queueItem(JsonObject object) {
        JsonObject executable = object(object, "executable");
        return new QueueItem(
            number(object, "id", -1),
            emptyToNull(string(object, "why")),
            bool(object, "cancelled"),
            string(executable, "url")
        );
    }

    /**
     * Stages from {@code <build>/wfapi/describe} of the Pipeline Stage View plugin.
     */
    public static List<CIBuildStage> stages(JsonObject describe) {
        List<CIBuildStage> result = new ArrayList<>();
        for (JsonObject stage : objects(describe, "stages")) {
            String id = string(stage, "id");
            if (id == null) {
                continue;
            }
            long start = number(stage, "startTimeMillis", 0);
            long duration = number(stage, "durationMillis", -1);
            result.add(new CIBuildStage(
                id,
                notNull(string(stage, "name")),
                null,
                stageStatus(string(stage, "status")),
                start > 0 ? Instant.ofEpochMilli(start) : null,
                duration >= 0 ? Duration.ofMillis(duration) : null,
                false,
                null
            ));
        }
        return result;
    }

    public static CIBuildStatus stageStatus(@Nullable String status) {
        if (status == null) {
            return CIBuildStatus.UNKNOWN;
        }
        return switch (status) {
            case "SUCCESS" -> CIBuildStatus.SUCCESS;
            case "UNSTABLE" -> CIBuildStatus.UNSTABLE;
            case "FAILED" -> CIBuildStatus.FAILURE;
            case "ABORTED" -> CIBuildStatus.ABORTED;
            case "NOT_EXECUTED" -> CIBuildStatus.NOT_BUILT;
            case "IN_PROGRESS", "PAUSED_PENDING_INPUT" -> CIBuildStatus.RUNNING;
            case "QUEUED" -> CIBuildStatus.QUEUED;
            default -> CIBuildStatus.UNKNOWN;
        };
    }

    /**
     * @param buildUrl URL of the build page, the artifacts are below {@code <build>/artifact/}
     */
    public static List<CIArtifact> artifacts(JsonObject build, String buildUrl) {
        List<CIArtifact> result = new ArrayList<>();
        for (JsonObject artifact : objects(build, "artifacts")) {
            String path = string(artifact, "relativePath");
            if (path == null) {
                continue;
            }
            result.add(new CIArtifact(path, path, -1, JenkinsUrls.withSlash(buildUrl) + "artifact/" + JenkinsUrls.encodePath(path)));
        }
        return result;
    }

    /**
     * A part of {@code <build>/logText/progressiveText?start=<offset>}.
     *
     * @param textSize {@code X-Text-Size} header: the offset of the next part, in bytes of the log file on the server
     * @param moreData {@code X-More-Data} header: {@code true} while the build is running
     */
    public static CILogChunk logChunk(String text, long offset, @Nullable String textSize, @Nullable String moreData) {
        long nextOffset = -1;
        if (textSize != null) {
            try {
                nextOffset = Long.parseLong(textSize.trim());
            }
            catch (NumberFormatException ignored) {
            }
        }
        if (nextOffset < 0) {
            nextOffset = offset + text.getBytes(StandardCharsets.UTF_8).length;
        }
        return new CILogChunk(text, nextOffset, !Boolean.parseBoolean(moreData == null ? null : moreData.trim()));
    }

    public static @Nullable JsonObject object(@Nullable JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(name);
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    public static List<JsonObject> objects(@Nullable JsonObject parent, String name) {
        List<JsonObject> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        JsonElement element = parent.get(name);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonObject()) {
                    result.add(item.getAsJsonObject());
                }
            }
        }
        return result;
    }

    public static List<String> strings(@Nullable JsonObject parent, String name) {
        List<String> result = new ArrayList<>();
        if (parent == null) {
            return result;
        }
        JsonElement element = parent.get(name);
        if (element != null && element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement item : array) {
                if (item.isJsonPrimitive()) {
                    result.add(item.getAsString());
                }
            }
        }
        return result;
    }

    public static @Nullable String string(@Nullable JsonObject parent, String name) {
        if (parent == null) {
            return null;
        }
        JsonElement element = parent.get(name);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    public static boolean bool(@Nullable JsonObject parent, String name) {
        if (parent == null) {
            return false;
        }
        JsonElement element = parent.get(name);
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean() && element.getAsBoolean();
    }

    public static long number(@Nullable JsonObject parent, String name, long defaultValue) {
        if (parent == null) {
            return defaultValue;
        }
        JsonElement element = parent.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return defaultValue;
        }
        return element.getAsLong();
    }

    private static String notNull(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static @Nullable String emptyToNull(@Nullable String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
