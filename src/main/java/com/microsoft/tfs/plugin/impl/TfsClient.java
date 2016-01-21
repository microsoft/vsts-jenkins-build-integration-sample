// Copyright (c) Microsoft. All rights reserved.
// Licensed under the MIT license. See License.txt in the project root.

package com.microsoft.tfs.plugin.impl;

import com.microsoft.teamfoundation.build.webapi.BuildHttpClient;
import com.microsoft.teamfoundation.core.webapi.CoreHttpClient;
import com.microsoft.teamfoundation.distributedtask.webapi.TaskHttpClient;
import hudson.Util;
import hudson.util.Secret;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.NTCredentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.AuthSchemes;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.glassfish.jersey.SslConfigurator;
import org.glassfish.jersey.apache.connector.ApacheClientProperties;
import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.client.RequestEntityProcessing;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import java.net.URI;
import java.util.logging.Logger;

/**
 * This class encapsulates all REST calls to TFS.
 *
 * The http connection uses basic auth to authenticate.
 */
public class TfsClient {
    private static final Logger logger = Logger.getLogger(TfsClient.class.getName());

    public static final String PROXY_URL_PROPERTY = "proxy_url";
    public static final String KEYSTORE_PATH_PROPERTY = "keystore_path";
    public static final String KEYSTORE_PASSWORD_PROPERTY = "keystore_password";
    public static final String TRUST_STORE_TYPE_PROPERTY = "trust_store_type";
    public static final String TRUST_MANAGER_FACTORY_ALGORITHM_PROPERTY = "trust_manager_factory_algorithm";
    public static final String SECURITY_PROTOCOL_PROPERTY = "security_protocol";

    private BuildHttpClient buildClient;
    private CoreHttpClient projectClient;
    private TaskHttpClient taskHttpClient;

    /*
     * Creating a tfs client
     */
    /* default */ TfsClient(URI uri, TfsClientFactoryImpl.ServiceProvider provider, String username, Secret password) {
        Client client = getClient(uri, provider, username, password);

        projectClient = new CoreHttpClient(client, uri);
        buildClient = new BuildHttpClient(client, uri);
        taskHttpClient = new TaskHttpClient(client, uri);
    }

    /**
     * get Build subarea client
     */
    public BuildHttpClient getBuildClient() {
        return buildClient;
    }

    /**
     * get Project subarea client
     */
    public CoreHttpClient getProjectClient() {
        return projectClient;
    }

    /**
     * get DistributedTask subarea client
     */
    public TaskHttpClient getTaskHttpClient() {
        return taskHttpClient;
    }

    /*
     * Creating a apache http client based JAX-RS client
     */
    private Client getClient(URI uri, TfsClientFactoryImpl.ServiceProvider provider, String username, Secret password) {
        ClientConfig clientConfig = new ClientConfig();

        CredentialsProvider credentialsProvider = new BasicCredentialsProvider();

        if (TfsClientFactoryImpl.ServiceProvider.TFS == provider) {
            /* NTLM auth for on premise installation */

            String domain = null;
            int idx = Math.max(username.indexOf('/'), username.indexOf('\\'));
            if (idx > 0) {
                domain = username.substring(0, idx);
                username = username.substring(idx + 1);
            }

            credentialsProvider.setCredentials(
                    new AuthScope(uri.getHost(), uri.getPort(), AuthScope.ANY_REALM, AuthSchemes.NTLM),
                    new NTCredentials(username, Secret.toString(password), uri.getHost(), domain));

            logger.info("Using NTLM authentication for on premise TeamFoundationServer");

        }  else if (TfsClientFactoryImpl.ServiceProvider.VSO == provider) {
            // Basic Auth for VSO services
            credentialsProvider.setCredentials(
                    new AuthScope(AuthScope.ANY_HOST, AuthScope.ANY_PORT),
                    new UsernamePasswordCredentials(username, Secret.toString(password)));

            logger.info("Using user/pass authentication for Visual Studio Online services");

            // Preemptive send basic auth header, or we will be redirected for oauth login
            clientConfig.property(ApacheClientProperties.PREEMPTIVE_BASIC_AUTHENTICATION, true);
        }

        clientConfig.property(ClientProperties.REQUEST_ENTITY_PROCESSING, RequestEntityProcessing.BUFFERED);

        if (System.getProperty(PROXY_URL_PROPERTY) != null) {
            clientConfig.property(ClientProperties.PROXY_URI, System.getProperty(PROXY_URL_PROPERTY));
            clientConfig.property(ApacheClientProperties.SSL_CONFIG, getSslConfigurator());
        }

        clientConfig.property(ApacheClientProperties.CREDENTIALS_PROVIDER, credentialsProvider);
        clientConfig.connectorProvider(new ApacheConnectorProvider());

        return ClientBuilder.newClient(clientConfig);
    }

    /*
     * Setting up ssl proxy
     */
    private static SslConfigurator getSslConfigurator() {
        String keystorePath = System.getProperty(KEYSTORE_PATH_PROPERTY);
        String keystorePassword = System.getProperty(KEYSTORE_PASSWORD_PROPERTY);

        String trustStoreType = System.getProperty(TRUST_STORE_TYPE_PROPERTY);
        if (Util.fixEmptyAndTrim(trustStoreType) == null) {
            trustStoreType = "JKS";
        }

        String trustManagerFactoryAlgorithm = System.getProperty(TRUST_MANAGER_FACTORY_ALGORITHM_PROPERTY);
        if (Util.fixEmptyAndTrim(trustManagerFactoryAlgorithm) == null) {
            trustManagerFactoryAlgorithm = "PKIX";
        }

        String securityProtocol = System.getProperty(SECURITY_PROTOCOL_PROPERTY);
        if (Util.fixEmptyAndTrim(securityProtocol) == null) {
            securityProtocol = "SSL";
        }

        SslConfigurator sslConfigurator = SslConfigurator.newInstance();
        return sslConfigurator.trustStoreFile(keystorePath)
                .trustStorePassword(keystorePassword)
                .trustStoreType(trustStoreType)
                .trustManagerFactoryAlgorithm(trustManagerFactoryAlgorithm)
                .securityProtocol(securityProtocol);
    }
}
