// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.tfs.plugin.Notifier;

import com.microsoft.teamfoundation.build.webapi.model.BuildDefinitionReference;
import com.microsoft.teamfoundation.build.webapi.model.DefinitionReference;
import com.microsoft.teamfoundation.core.webapi.model.TeamProjectReference;
import com.microsoft.tfs.plugin.TfsBuildFacade;
import com.microsoft.tfs.plugin.TfsBuildFacadeFactory;
import com.microsoft.tfs.plugin.TfsClientFactory;
import com.microsoft.tfs.plugin.impl.TfsBuildFacadeFactoryImpl;
import com.microsoft.tfs.plugin.impl.TfsClient;
import com.microsoft.tfs.plugin.TfsConfiguration;
import com.microsoft.tfs.plugin.impl.TfsClientFactoryImpl;
import com.microsoft.vss.client.core.model.VssServiceException;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

/**
 *  Send jenkins build outcome to the queued build container in Microsoft TFS
 */
public class TfsBuildNotifier extends Notifier {

    private static final Logger logger = Logger.getLogger(TfsBuildNotifier.class.getName());

    public final String serverUrl;
    public final String username;
    public final Secret password;
    public final String project;
    public final String buildDefinition;

    private transient TfsClientFactory tfsClientFactory;
    private transient TfsBuildFacadeFactory tfsBuildFacadeFactory;

    @DataBoundConstructor
    public TfsBuildNotifier(String serverUrl, String username, Secret password, String project, String buildDefinition) {
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
        this.project = project;
        this.buildDefinition = buildDefinition;
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public boolean needsToRunAfterFinalized() {
        /* We need to run after build is marked complete to get the accurate build duration time
           to send to VSO.  We can not fail the build at this point.
        */
        return true;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {

        // Get VSO build environments
        Map<String, String> env = build.getBuildVariables();
        String tfsBuildIdStr = env.get("TfsBuildId" + build.getId());

        // No build was queued on tfs, return
        if (tfsBuildIdStr == null) {
            return false;
        }

        int tfsBuildId = Integer.parseInt(tfsBuildIdStr);
        try {
            TfsClient client = getTfsClientFactory().getValidatedClient(this.serverUrl, this.username, this.password);
            TfsBuildFacade tfsBuildFacade = getTfsBuildFacadeFactory().getBuildOnTfs(tfsBuildId, build, client);

            tfsBuildFacade.finishAllTaskRecords();
            tfsBuildFacade.finishBuild();

        } catch (Exception e) {
            e.printStackTrace();
            logger.severe(e.getMessage());

            return false;
        }

        return true;
    }

    public TfsConfiguration getConfig() {
        return new TfsConfiguration(serverUrl, username, password, project, buildDefinition);
    }

    public void setTfsBuildFacadeFactory(TfsBuildFacadeFactory facadeFactory) {
        this.tfsBuildFacadeFactory = facadeFactory;
    }

    private TfsBuildFacadeFactory getTfsBuildFacadeFactory() {
        if (this.tfsBuildFacadeFactory == null) {
            this.tfsBuildFacadeFactory = new TfsBuildFacadeFactoryImpl();
        }

        return this.tfsBuildFacadeFactory;
    }

    public void setTfsClientFactory(TfsClientFactory clientFactory) {
        this.tfsClientFactory = clientFactory;
    }

    public TfsClientFactory getTfsClientFactory() {
        if (this.tfsClientFactory == null) {
            this.tfsClientFactory = new TfsClientFactoryImpl();
        }

        return tfsClientFactory;
    }

    @Extension
    public static class Descriptor extends BuildStepDescriptor<Publisher> {

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }

        private transient TfsClientFactory tfsClientFactory;

        public String getDisplayName() {
            return "TFS Notifier";
        }

        public FormValidation doCheckServerUrl(@QueryParameter String serverUrl) {
            if (serverUrl == null || serverUrl.trim().isEmpty()) {
                return FormValidation.error("Please enter the URL for Team Foundation Service host");
            }

            try {
                new URL(serverUrl);

                return FormValidation.ok();

            } catch (MalformedURLException e) {
                return FormValidation.error(e.getLocalizedMessage());
            }
        }

        public FormValidation doCheckUsername(@QueryParameter String username) {
            return FormValidation.validateRequired(username);
        }

        public FormValidation doCheckPassword(@QueryParameter String password) {
            return FormValidation.validateRequired(password);
        }

        public FormValidation doTestConnection(@QueryParameter String serverUrl, @QueryParameter String username,
                                               @QueryParameter Secret password) {
            try {
                if (!validInputs(serverUrl, username, password)) {
                    return FormValidation.error("Input fields are invalid");
                }
                getTfsClientFactory().getValidatedClient(serverUrl, username, password);

                return FormValidation.ok("Successfully connected to server "+serverUrl);

            } catch (URISyntaxException e) {
                e.printStackTrace();
                return FormValidation.error(e.getMessage());
            } catch (VssServiceException e) {
                e.printStackTrace();
                return FormValidation.error("Could not connect to the server based on the input, please double check the username and password specified.");
            }
        }

        public ListBoxModel doFillProjectItems(@QueryParameter String serverUrl, @QueryParameter String username,
                                               @QueryParameter Secret password) throws URISyntaxException {
            ListBoxModel items = new ListBoxModel();

            if (validInputs(serverUrl, username, password)) {
                try {
                    TfsClient client = getTfsClientFactory().getValidatedClient(serverUrl, username, password);
                    List<TeamProjectReference> references = client.getProjectClient().getProjects();

                    for (TeamProjectReference ref : references) {
                        items.add(ref.getName(), String.valueOf(ref.getId()));
                    }
                } catch (VssServiceException vse) {
                    return items;
                }
            }

            return items;
        }

        public ListBoxModel doFillBuildDefinitionItems(@QueryParameter String serverUrl,  @QueryParameter String username,
                                                       @QueryParameter Secret password, @QueryParameter String project) throws URISyntaxException {
            ListBoxModel items = new ListBoxModel();

            if (validInputs(serverUrl, username, password, project)) {
                try {
                    TfsClient client = getTfsClientFactory().getValidatedClient(serverUrl, username, password);
                    List<DefinitionReference> definitions = client.getBuildClient().getDefinitions(UUID.fromString(project));

                    for (DefinitionReference definition : definitions) {
                        items.add(definition.getName(), String.valueOf(definition.getId()));
                    }
                } catch (VssServiceException vse) {
                    return items;
                }
            }

            return items;
        }

        private boolean validInputs(Object... inputs) {
            for (Object input : inputs) {
                if (input == null) {
                    return false;
                }

                if (input instanceof String && Util.fixEmptyAndTrim((String) input) == null) {
                    return false;
                }

                if (input instanceof Secret && Util.fixEmptyAndTrim(Secret.toString((Secret)input)) == null) {
                    return false;
                }
            }

            return true;
        }

        public void setTfsClientFactory(TfsClientFactory clientFactory) {
            this.tfsClientFactory = clientFactory;
        }

        public TfsClientFactory getTfsClientFactory() {
            if (this.tfsClientFactory == null) {
                this.tfsClientFactory = new TfsClientFactoryImpl();
            }

            return this.tfsClientFactory;
        }
    }
}
