// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.tfs.plugin;

import com.microsoft.tfs.plugin.impl.TfsClient;
import hudson.model.AbstractBuild;

/**
 * Create a facade that can be used to update TFS builds
 */
public interface TfsBuildFacadeFactory {

    /**
     * Create (queue) a build on TFS side that acts like a container for this jenkins build
     *
     * @param jenkinsBuild
     * @param tfsClient
     * @return a TfsBuildInstance
     */
    TfsBuildFacade createBuildOnTfs(final String projectId, int buildDefinition,
                                            final AbstractBuild jenkinsBuild, final TfsClient tfsClient);


    /**
     * Get a TfsBuildFacade when a build has been already queued on TFS side
     *
     * @param tfsBuildId
     * @param jenkinsBuild
     * @param tfsClient
     */
    TfsBuildFacade getBuildOnTfs(final int tfsBuildId, final AbstractBuild jenkinsBuild, final TfsClient tfsClient);
}
