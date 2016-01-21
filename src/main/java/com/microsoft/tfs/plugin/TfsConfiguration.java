// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.tfs.plugin;

import hudson.util.Secret;

import java.io.Serializable;

public class TfsConfiguration implements Serializable {

    private static final long serialVersionUID = 4466932169999247360L;

    private final String serverUrl;
    private final String username;
    private final Secret password;
    private final String project;
    private final String buildDefinition;

    public TfsConfiguration(String serverUrl, String username, Secret password, String project, String buildDefinition) {
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
        this.project = project;
        this.buildDefinition = buildDefinition;
    }

    public String getBuildDefinition() {
        return buildDefinition;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getUsername() {
        return username;
    }

    public Secret getPassword() {
        return password;
    }

    public String getProject() {
        return project;
    }

    public String toString() {
        return String.format("server: %s, user: %s, project: %s, build definition: %s",
                serverUrl, username, project, buildDefinition);
    }
}
