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
import consulo.accountManagement.AccountAuthorizeConfig;
import consulo.accountManagement.AccountConnection;
import consulo.accountManagement.AccountDetails;
import consulo.accountManagement.AccountType;
import consulo.accountManagement.NoScope;
import consulo.accountManagement.authorize.AnonymousAuthorizeConfig;
import consulo.accountManagement.authorize.BasicAuthorizeConfig;
import consulo.annotation.component.ExtensionImpl;
import consulo.jenkins.icon.JenkinsIconGroup;
import consulo.jenkins.localize.JenkinsLocalize;
import consulo.localize.LocalizeValue;
import consulo.ui.image.Image;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Jenkins signs in with the user name and an API token over basic authentication (an API token also makes POST
 * requests exempt from CSRF crumbs). Servers with public read access, like {@code https://ci.consulo.io}, are used
 * anonymously.
 *
 * @author VISTALL
 * @since 2026-11-09
 */
@ExtensionImpl
public class JenkinsAccountType implements AccountType<NoScope> {
    public static final String ID = "jenkins";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public LocalizeValue getName() {
        return JenkinsLocalize.jenkinsName();
    }

    @Override
    public Image getIcon() {
        return JenkinsIconGroup.jenkins();
    }

    @Override
    public Class<NoScope> getScopeClass() {
        return NoScope.class;
    }

    @Override
    public boolean isServerEditable() {
        return true;
    }

    @Override
    public List<? extends AccountAuthorizeConfig> getAuthorizeConfigs() {
        return List.of(
            new BasicAuthorizeConfig() {
                @Override
                public boolean isDefault() {
                    return true;
                }
            },
            new AnonymousAuthorizeConfig() {
            }
        );
    }

    @Override
    public CompletableFuture<AccountDetails> loadDetails(AccountConnection connection) {
        JenkinsClient client;
        try {
            client = JenkinsClient.create(connection, null);
        }
        catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }

        String rootUrl = client.getRootUrl();
        return client.getJson(JenkinsUrls.api(rootUrl + "whoAmI/", JenkinsJson.WHO_AM_I_TREE)).thenCompose(whoAmI -> {
            if (JenkinsJson.bool(whoAmI, "anonymous")) {
                return CompletableFuture.completedFuture(new AccountDetails(JenkinsUrls.presentableName(rootUrl), null, null));
            }

            String userId = JenkinsJson.string(whoAmI, "name");
            return client.getJson(JenkinsUrls.api(rootUrl + "me/", JenkinsJson.USER_TREE))
                .handle((user, error) -> toDetails(userId, error == null ? user : null));
        });
    }

    private static AccountDetails toDetails(@Nullable String userId, @Nullable JsonObject user) {
        String login = JenkinsJson.string(user, "id");
        if (login == null) {
            login = userId;
        }
        String fullName = JenkinsJson.string(user, "fullName");
        String name = fullName != null ? fullName : login;
        return new AccountDetails(name == null ? "" : name, login, null);
    }
}
