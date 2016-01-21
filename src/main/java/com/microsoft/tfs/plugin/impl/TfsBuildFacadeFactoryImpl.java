// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.tfs.plugin.impl;

import com.microsoft.teamfoundation.build.webapi.model.*;
import com.microsoft.teamfoundation.core.webapi.model.TeamProjectReference;
import com.microsoft.tfs.plugin.TfsBuildFacade;
import com.microsoft.tfs.plugin.TfsBuildFacadeFactory;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

public class TfsBuildFacadeFactoryImpl implements TfsBuildFacadeFactory {

    private static final Logger logger = Logger.getLogger(TfsBuildFacadeFactoryImpl.class.getName());

    public TfsBuildFacade createBuildOnTfs(String projectId, int buildDefinition, AbstractBuild jenkinsBuild, TfsClient tfsClient) {
        if (jenkinsBuild == null || tfsClient == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }

        TeamProjectReference project = tfsClient.getProjectClient().getProject(projectId);
        if (project == null) {
            throw new RuntimeException(String.format("Could not find the project: %s", projectId));
        }

        DefinitionReference definition = tfsClient.getBuildClient().getDefinition(project.getId(), buildDefinition, null, null);
        if (definition == null) {
            throw new RuntimeException(String.format("Could not find the buildDefinition: %d", buildDefinition));
        }

        List<AgentPoolQueue> queues = tfsClient.getBuildClient().getQueues(null);
        if (queues == null || queues.isEmpty()) {
            logger.info("Creating JenkinsPluginQueue on TeamFoundationServer");
            queues = createTfsBuildQueue(tfsClient);
        }

        AgentPoolQueue anyQueue = queues.get(0);
        Build buildContainer = createBuildContainer(project, definition, anyQueue, jenkinsBuild.getProject().getScm());
        Build queuedBuild = tfsClient.getBuildClient().queueBuild(buildContainer, true);

        logger.info(String.format("Queued build on TFS with plan Id %s", queuedBuild.getOrchestrationPlan().getPlanId()));

        return new TfsBuildFacadeImpl(queuedBuild, jenkinsBuild, tfsClient);
    }

    public TfsBuildFacade getBuildOnTfs(int tfsBuildId, AbstractBuild jenkinsBuild, TfsClient tfsClient) {
        Build tfsBuild = tfsClient.getBuildClient().getBuild(tfsBuildId, null);

        return new TfsBuildFacadeImpl(tfsBuild, jenkinsBuild, tfsClient);
    }

    private List<AgentPoolQueue> createTfsBuildQueue(TfsClient tfsClient) {
        AgentPoolQueue queue = new AgentPoolQueue();
        queue.setName("JenkinsPluginQueue");

        queue = tfsClient.getBuildClient().createQueue(queue);

        return Collections.singletonList(queue);
    }

    private Build createBuildContainer(TeamProjectReference project, DefinitionReference definition, AgentPoolQueue queue, SCM scm) {
        Build b = new Build();
        b.setQueue(queue);
        b.setDefinition(definition);
        b.setProject(project);

        b.setParameters("{\"build.config\":\"Jenkins\"}");
        b.setDemands(Collections.<Demand>emptyList());
        b.setQueueOptions(QueueOptions.DO_NOT_RUN);

        b.setSourceBranch(getBranch(scm));

        return b;
    }

    private String getBranch(SCM scm) {
        if (scm instanceof GitSCM) {
            GitSCM gitScm = (GitSCM) scm;
            StringBuilder builder = new StringBuilder();
            for (BranchSpec spec : gitScm.getBranches()) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(spec.getName());
            }

            return builder.toString();
        }

        // here we can try to get branch from other SCM
        return "undetermined";
    }

    // If we decided not to depend on Git plugin, will need to use this method
    private String getGitBranchViaReflection(SCM scm) {
        // Use reflection to attempt to get build branch, this assumes Git plugin is used as the SCM
        // If Git plugin isn't used, should just leave the source branch as "undetermined"
        Object branch = "undetermined";
        try {
            if (scm != null) {
                Class scmClazz = scm.getClass();
                Method getBranches = null;
                for (Method m : scmClazz.getMethods()) {
                    if ("getBranches".equals(m.getName())) {
                        getBranches = m;
                        break;
                    }
                }

                if (getBranches != null) {
                    Object branchesList = getBranches.invoke(scm);
                    if (branchesList != null) {
                        if (branchesList instanceof List && !((List) branchesList).isEmpty()) {
                            branch = ((List) branchesList).get(0);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // suppress
            logger.warning("Retrieving branch name through reflection failed: "+e.getMessage());
        }

        String branchStr = Util.fixEmptyAndTrim(branch.toString());
        if (branchStr != null && branchStr.equals("**")) {
            return "any";
        }

        return branchStr;
    }
}
