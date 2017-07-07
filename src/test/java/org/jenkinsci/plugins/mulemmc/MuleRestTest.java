package org.jenkinsci.plugins.mulemmc;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.mockito.Mockito.when;

import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.protocol.HttpContext;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.Set;

/**
 * Created by christianlangmann on 02.06.17.
 */
public class MuleRestTest {

    public static final String MMC_URL = "http://www.test.de";
    public static final int STATUS_OK = 200;

    @Test
    public void restfullyGetClustersOfGroupIdTest() throws Exception {
        String responseBody = "getAllClusters.json";
        CloseableHttpClient mockHttpClient = Mockito.mock(CloseableHttpClient.class);

        CloseableHttpResponse response = prepareResponse(STATUS_OK, responseBody);
        when(mockHttpClient.execute(Mockito.any(HttpHost.class), Mockito.any(HttpRequest.class), Mockito.any(HttpContext.class)))
                .thenReturn(response);

        MuleRest rest = new MuleRest(new URL(MMC_URL), mockHttpClient, null);
        Set<String> clusters = rest.restfullyGetClustersOfGroupId("3a450e96-bd4d-48f1-bca0-afcbfab8e0cb");

        assertThat(clusters, contains("5124dec9-cac0-49d5-ae4f-13168e945c02"));
    }

    @Test
    public void restfullyGetClustersOfGroupIdNoMatch() throws Exception {
        String responseBody = "getAllClusters.json";
        CloseableHttpClient mockHttpClient = Mockito.mock(CloseableHttpClient.class);

        CloseableHttpResponse response = prepareResponse(STATUS_OK, responseBody);
        when(mockHttpClient.execute(Mockito.any(HttpHost.class), Mockito.any(HttpRequest.class), Mockito.any(HttpContext.class)))
                .thenReturn(response);

        MuleRest rest = new MuleRest(new URL(MMC_URL), mockHttpClient, null);
        Set<String> clusters = rest.restfullyGetClustersOfGroupId("DummyValue");

        assertThat(clusters.isEmpty(), is(true));
    }

    private CloseableHttpResponse prepareResponse(int expectedResponseStatus,
                                         String expectedResponseResource) throws IOException {
        CloseableHttpResponse response = Mockito.mock(CloseableHttpResponse.class);
        StatusLine mockStatusLine = Mockito.mock(StatusLine.class);
        when(mockStatusLine.getStatusCode()).thenReturn(expectedResponseStatus);
        when(response.getStatusLine()).thenReturn(mockStatusLine);
        HttpEntity entity = Mockito.mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(getClass().getClassLoader().getResourceAsStream(expectedResponseResource));
        when(response.getEntity()).thenReturn(entity);
        return response;
    }
}
