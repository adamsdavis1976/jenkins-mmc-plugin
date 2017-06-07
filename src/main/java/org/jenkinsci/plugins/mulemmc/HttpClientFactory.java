package org.jenkinsci.plugins.mulemmc;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.*;
import org.apache.http.impl.conn.BasicHttpClientConnectionManager;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.logging.Logger;

/**
 * Created by christianlangmann on 05.06.17.
 */
public class HttpClientFactory {

    private URL mmcUrl;
    private String username;
    private String password;

    private static final Logger logger = Logger.getLogger(HttpClientFactory.class.getName());

    public HttpClientFactory(String url, String username, String password) throws MalformedURLException {
        logger.fine("HttpClientFactory " + url + " " + username);
        mmcUrl = new URL(url);
        this.username = username;
        this.password = password;
    }

    public CloseableHttpClient createHttpClient() {
        logger.fine("createHttpClient");
        HttpClientBuilder builder = HttpClientBuilder.create();
        builder.setRedirectStrategy(new LaxRedirectStrategy());
/*
        CredentialsProvider credsProvider = new BasicCredentialsProvider();

        credsProvider.setCredentials(
                new AuthScope(mmcUrl.getHost(), mmcUrl.getPort()),
                new UsernamePasswordCredentials(username, password));
        // Create AuthCache instance
        AuthCache authCache = new BasicAuthCache();
        // Generate BASIC scheme object and add it to the local auth cache
        BasicScheme basicAuth = new BasicScheme();
        HttpHost host = new HttpHost(mmcUrl.getHost(), mmcUrl.getPort(), mmcUrl.getProtocol());
        authCache.put(host, basicAuth);
        // Set the default credentials provider
        builder.setDefaultCredentialsProvider(credsProvider);
*/
        return builder.build();
    }

    public HttpCoreContext createHttpContext()
    {
        logger.fine("HttpClientFactory createHttpContext");
        CredentialsProvider credsProvider = new BasicCredentialsProvider();
        credsProvider.setCredentials(
                new AuthScope(mmcUrl.getHost(), mmcUrl.getPort()),
                new UsernamePasswordCredentials(username, password));
        // Create AuthCache instance
        AuthCache authCache = new BasicAuthCache();
        // Generate BASIC scheme object and add it to the local auth cache
        BasicScheme basicAuth = new BasicScheme();
        HttpHost host = new HttpHost(mmcUrl.getHost(), mmcUrl.getPort(), mmcUrl.getProtocol());
        authCache.put(host, basicAuth);

        // Add AuthCache to the execution context
        HttpClientContext context =  HttpClientContext.create();
        context.setCredentialsProvider(credsProvider);
        context.setAuthCache(authCache);
        return context;
    }

}
