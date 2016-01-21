// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.tfs.plugin.BuildWrapper;

import com.microsoft.tfs.plugin.Notifier.TfsBuildNotifier;
import com.microsoft.tfs.plugin.*;
import com.microsoft.tfs.plugin.impl.TfsBuildFacadeFactoryImpl;
import com.microsoft.tfs.plugin.impl.TfsClient;
import com.microsoft.tfs.plugin.impl.TfsClientFactoryImpl;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Describable;
import hudson.tasks.BuildWrapper;
import hudson.tasks.BuildWrapperDescriptor;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.logging.Logger;

/**
 *  Microsoft TFS Build vNext build wrapper
 *
 *  Create a build container on TFS server and decorate the logger to pipe
 *  output to TFS build instance console
 */
public class TfsBuildWrapper extends BuildWrapper {

    private static final Logger logger = Logger.getLogger(TfsBuildWrapper.class.getName());

    private transient TfsBuildFacadeFactory tfsBuildFacadeFactory;
    private transient TfsClientFactory tfsClientFactory;
    private transient TfsBuildFacade tfsBuildFacade;

    @DataBoundConstructor
    public TfsBuildWrapper() {
    }

    @Override
    public Environment setUp(AbstractBuild build, Launcher launcher, BuildListener listener) {
        return new Environment() {};
    }

    @Override
    public OutputStream decorateLogger(AbstractBuild build, OutputStream os) throws FileNotFoundException {
        DescribableList describableList = build.getProject().getPublishersList();
        if (describableList != null) {
            @SuppressWarnings("unchecked")
            Describable notifier = describableList.get(Jenkins.getInstance().getDescriptor(TfsBuildNotifier.class));

            if (notifier != null && notifier instanceof TfsBuildNotifier) {
                TfsConfiguration config = ((TfsBuildNotifier) notifier).getConfig();

                try {
                    tfsBuildFacade = getTfsBuildFacadeFactory().createBuildOnTfs(config.getProject(),
                            Integer.parseInt(config.getBuildDefinition()), build, getClient(config));

                } catch (Exception e) {
                    String msg = String.format("Failed to queue a build on Microsoft TFS with exception: %s%n", e.getMessage());
                    logger.info(msg);
                    e.printStackTrace();
                    writeQuietly(os, msg.getBytes(Charset.defaultCharset()));
                }

                if (tfsBuildFacade == null) {
                    String msg = "Build is not queue on Microsoft TFS, no log will be send to Microsoft TFS.\n";
                    logger.info(msg);
                    writeQuietly(os, msg.getBytes(Charset.defaultCharset()));

                    return os;
                }

                tfsBuildFacade.startBuild();
                tfsBuildFacade.startAllTaskRecords();

                // Post logs to TFS server's build console
                TfsRemoteConsoleLogAppender appender = new TfsRemoteConsoleLogAppender(os, tfsBuildFacade);
                appender.start();

                return appender;
            }
        }

        String msg = "TfsBuildNotifier is not configured, do not decorate the output logger.\n";
        logger.info(msg);
        writeQuietly(os, msg.getBytes(Charset.defaultCharset()));
        return os;
    }

    @Override
    public void makeBuildVariables(AbstractBuild build, Map<String,String> env) {
        if (this.tfsBuildFacade != null) {
            env.put("TfsBuildId" + build.getId(), String.valueOf(this.tfsBuildFacade.getTfsBuildId()));
        }
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

    private TfsClient getClient(TfsConfiguration config) throws URISyntaxException {
        return getTfsClientFactory().getValidatedClient(config.getServerUrl(), config.getUsername(), config.getPassword());
    }

    private void writeQuietly(OutputStream os, byte[] msg) {
        if (os != null) {
            try {
                os.write(msg);
            } catch (IOException ioe) {
                logger.severe(ioe.getMessage());
                //suppress
            }
        }
    }

    @Extension
    public static class TfsBuildWrapperDescriptorImpl extends BuildWrapperDescriptor {
        public TfsBuildWrapperDescriptorImpl() {
            super(TfsBuildWrapper.class);
            load();
        }

        public String getDisplayName() {
            return "Send build log to Microsoft TeamFoundationServer";
        }

        public boolean isApplicable(AbstractProject<?, ?> item) {
            return true;
        }
    }
}
