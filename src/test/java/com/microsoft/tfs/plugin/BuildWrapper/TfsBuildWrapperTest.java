// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.tfs.plugin.BuildWrapper;

import com.microsoft.tfs.plugin.Notifier.TfsBuildNotifier;
import com.microsoft.tfs.plugin.TfsBuildFacade;
import com.microsoft.tfs.plugin.TfsBuildFacadeFactory;
import com.microsoft.tfs.plugin.TfsClientFactory;
import com.microsoft.tfs.plugin.impl.TfsClient;
import hudson.model.AbstractBuild;
import hudson.model.FreeStyleProject;
import hudson.util.Secret;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.io.*;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;

public class TfsBuildWrapperTest {

    TfsBuildWrapper underTest;

    AbstractBuild jenkinsBuild;

    @Mock
    TfsBuildFacadeFactory facadeFactoryMock;

    @Mock
    TfsBuildFacade buildFacadeMock;

    @Mock
    TfsClientFactory clientFactoryMock;

    @Mock
    TfsClient tfsClientMock;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Before
    public void setUp() throws Exception {
        underTest = new TfsBuildWrapper();

        facadeFactoryMock = Mockito.mock(TfsBuildFacadeFactory.class);
        buildFacadeMock = Mockito.mock(TfsBuildFacade.class);
        clientFactoryMock = Mockito.mock(TfsClientFactory.class);
        tfsClientMock = Mockito.mock(TfsClient.class);

        underTest.setTfsBuildFacadeFactory(facadeFactoryMock);
        underTest.setTfsClientFactory(clientFactoryMock);

        when(facadeFactoryMock.createBuildOnTfs(anyString(), anyInt(), any(AbstractBuild.class), any(TfsClient.class)))
                .thenReturn(buildFacadeMock);

        when(clientFactoryMock.getValidatedClient(anyString(), anyString(), any(Secret.class)))
                .thenReturn(tfsClientMock);

        when(buildFacadeMock.getTfsBuildId()).thenReturn(1);
    }

    @After
    public void tearDown() throws Exception {
        jenkinsBuild = null;
    }

    @Test
    public void loggerShouldBeDecorated() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        project.getPublishersList().add(new TfsBuildNotifier("http://testurl.com", "tester", Secret.fromString("testpass"), "testProj", "1"));
        jenkinsBuild = project.scheduleBuild2(0).get();

        OutputStream os = new ByteArrayOutputStream(2048);
        OutputStream appender = underTest.decorateLogger(jenkinsBuild, os);

        verify(buildFacadeMock, times(1)).startBuild();
        verify(buildFacadeMock, times(1)).startAllTaskRecords();

        assertNotNull("Did not create a log appender for TFS", appender);

        // verify we properly set environments, this tests makeBuildVariables
        Map<String,String> env = new HashMap<String, String>();
        underTest.makeBuildVariables(jenkinsBuild, env);

        assertTrue("Env is empty", env.size() > 0);
        assertEquals("build is should be 1", 1, Integer.parseInt(env.get("TfsBuildId" + jenkinsBuild.getId())));
    }

    @Test
    public void skipDecorateLoggerIfNoNotifier() throws Exception {
        FreeStyleProject project = j.createFreeStyleProject();
        jenkinsBuild = project.scheduleBuild2(0).get();

        ByteArrayOutputStream os = new ByteArrayOutputStream(2048);
        OutputStream appender = underTest.decorateLogger(jenkinsBuild, os);

        verify(buildFacadeMock, never()).startBuild();
        verify(buildFacadeMock, never()).startAllTaskRecords();

        // reference check, not equality check since we should just return without decorating
        assertTrue("Created a log appender for TFS without TFS configuration!", os == appender);

        ByteArrayInputStream is = new ByteArrayInputStream(os.toByteArray());
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));

        String log = reader.readLine();
        assertTrue("Log not displayed on screen", log.indexOf("not configured") > 0);
    }
}
