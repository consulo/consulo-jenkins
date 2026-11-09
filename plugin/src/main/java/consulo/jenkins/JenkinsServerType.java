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

import consulo.accountManagement.AccountManager;
import consulo.annotation.component.ExtensionImpl;
import consulo.continuousIntegration.CIServer;
import consulo.continuousIntegration.CIServerType;
import consulo.jenkins.icon.JenkinsIconGroup;
import consulo.jenkins.localize.JenkinsLocalize;
import consulo.localize.LocalizeValue;
import consulo.project.Project;
import consulo.ui.image.Image;
import jakarta.inject.Inject;

/**
 * Every Jenkins account is detected as a server, so a server needs no setup beyond signing in.
 *
 * @author VISTALL
 * @since 2026-11-09
 */
@ExtensionImpl
public class JenkinsServerType extends CIServerType<JenkinsServerOptions> {
    public static final String ID = "jenkins";

    private final AccountManager myAccountManager;

    @Inject
    public JenkinsServerType(AccountManager accountManager) {
        myAccountManager = accountManager;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public LocalizeValue getPresentableName() {
        return JenkinsLocalize.jenkinsName();
    }

    @Override
    public Image getIcon() {
        return JenkinsIconGroup.jenkins();
    }

    @Override
    public String getAccountTypeId() {
        return JenkinsAccountType.ID;
    }

    @Override
    public JenkinsServerOptions createOptions() {
        return new JenkinsServerOptions();
    }

    @Override
    public CIServer createServer(Project project, JenkinsServerOptions options) {
        return new JenkinsServer(this, project, options, myAccountManager);
    }
}
