// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.tfs.plugin.Notifier;

import com.microsoft.tfs.plugin.TfsBuildFacade;
import com.microsoft.tfs.plugin.TfsBuildFacadeFactory;
import com.microsoft.tfs.plugin.TfsClientFactory;
import com.microsoft.tfs.plugin.impl.TfsClient;
import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.util.Secret;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TfsBuildNotifierTest {

    TfsBuildNotifier underTest;

    @Mock
    TfsBuildFacadeFactory facadeFactoryMock;

    @Mock
    TfsBuildFacade buildFacadeMock;

    @Mock
    TfsClientFactory clientFactoryMock;

    @Mock
    TfsClient tfsClientMock;

    @Mock
    AbstractBuild jenkinsBuildMock;

    @Mock
    BuildListener listenerMock;

    @Rule
    // Need Secret to create TfsClient, otherwise should not instantiate a jenkins instance - this takes well over 30s on my dev box
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() throws Exception {
        underTest = new TfsBuildNotifier("http://testurl.com", "tester", Secret.fromString("testpass"), "testProj", "1");

        facadeFactoryMock = Mockito.mock(TfsBuildFacadeFactory.class);
        buildFacadeMock = Mockito.mock(TfsBuildFacade.class);
        jenkinsBuildMock = Mockito.mock(AbstractBuild.class);
        listenerMock = Mockito.mock(BuildListener.class);
        clientFactoryMock = Mockito.mock(TfsClientFactory.class);
        tfsClientMock = Mockito.mock(TfsClient.class);

        underTest.setTfsBuildFacadeFactory(facadeFactoryMock);
        underTest.setTfsClientFactory(clientFactoryMock);

        when(facadeFactoryMock.getBuildOnTfs(anyInt(), any(AbstractBuild.class), any(TfsClient.class)))
                .thenReturn(buildFacadeMock);

        when(clientFactoryMock.getValidatedClient(anyString(), anyString(), any(Secret.class)))
                .thenReturn(tfsClientMock);

        when(buildFacadeMock.getTfsBuildId()).thenReturn(1);

        when(jenkinsBuildMock.getId()).thenReturn("jenkins1");
        Map<String, String> env = new HashMap<String, String>();
        env.put("TfsBuildIdjenkins1", "1");
        when(jenkinsBuildMock.getBuildVariables()).thenReturn(env);
        when(jenkinsBuildMock.getEnvironment(listenerMock)).thenReturn(new EnvVars());
    }

    @Test
    public void testPerform() throws Exception {
        boolean result = underTest.perform(jenkinsBuildMock, null, listenerMock);

        verify(buildFacadeMock).finishBuild();
        verify(buildFacadeMock).finishAllTaskRecords();

        assertTrue(result);
    }
}