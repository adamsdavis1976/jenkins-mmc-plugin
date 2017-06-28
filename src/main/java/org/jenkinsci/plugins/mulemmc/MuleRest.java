package org.jenkinsci.plugins.mulemmc;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.http.*;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.BufferedHttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.entity.mime.content.FileBody;
import org.apache.http.entity.mime.content.StringBody;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;

import javax.ws.rs.core.Response.Status;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

public class MuleRest
{

    public static final String STATUS_FIELD_NAME = "status";
    public static final String NAME_FIELD_NAME = "name";
    public static final String DATA_FIELD_NAME = "data";
    public static final String ID_FIELD_NAME = "id";
    public static final String VERSIONS_FIELD_NAME = "versions";

    private enum SERVER_TYPE {
        SERVER("server"),
        GROUP("server"),
        CLUSTER("cluster");

        SERVER_TYPE(String queryString) {
            this.queryString = queryString;
        }
        String queryString;
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final Logger logger = Logger.getLogger(MuleRest.class.getName());

	public static final String STATUS_DEPLOYED = "DEPLOYED";

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private HttpContext context;
    private HttpHost mmcHost;
    private String mmcPath;
    private CloseableHttpClient mmcHttpClient = null;

    public MuleRest(URL mmcUrl, CloseableHttpClient httpClient, HttpContext context) {
        logger.info("MuleRest: URL: " + mmcUrl);
		this.mmcHost = new HttpHost(mmcUrl.getHost(), mmcUrl.getPort(), mmcUrl.getProtocol());
		this.mmcPath = mmcUrl.getPath();
        this.mmcHttpClient = httpClient;
		this.context = context;
	}
	
	private void processResponseCode(HttpResponse response) throws Exception
	{
	    int code = response.getStatusLine().getStatusCode();
        logger.finer(">>>> processResponseCode " + code);

		if (code == HttpStatus.SC_OK)
		    return;

        String content = EntityUtils.toString(response.getEntity());

        if (code == Status.NOT_FOUND.getStatusCode())
		    throw new Exception("The resource was not found. " + content);
		else if (code == Status.CONFLICT.getStatusCode())
	        throw new Exception("The operation was unsuccessful because a resource with that name already exists. " + content);
		else if (code == Status.INTERNAL_SERVER_ERROR.getStatusCode())
		    throw new Exception("The operation was unsuccessful. " + content);
		else
            throw new Exception("Unexpected Status Code Return, Status Line: " + response.getStatusLine() + " " + content);
	}

    /**
     * Create a new deployment in MMC
     * @param deploymentName name of the deployment
     * @param targetName id of the deployment-target. can be either a single Server, a ServerGroup or a Cluster
     * @param versionId id of the version-application to deploy
     * @return id of the generated deployment
     * @throws Exception json handling failed
     */
	public String restfullyCreateDeployment(String deploymentName, String targetName, String versionId) throws Exception {
		logger.fine(">>>> restfullyCreateDeployment " + deploymentName + " " + targetName + " " + versionId);

		StringWriter stringWriter = new StringWriter();
		JsonGenerator jGenerator = JSON_FACTORY.createGenerator(stringWriter);
		jGenerator.writeStartObject(); // {

		writeName(deploymentName, jGenerator);
		// server and serverGroups are handled the same
		String targetId = restfullyGetServerGroupId(targetName);
		if (targetId == null) {
			targetId = restfullyGetServerId(targetName);
		}
		if (targetId != null) {
			writeServers(targetId, jGenerator);
		} else {
			targetId = restfullyGetClusterId(targetName);
			if (targetId != null)
				writeClusters(targetId, jGenerator);
			else
				throw new IllegalArgumentException("no server, server group or cluster found having the name " + targetName);
		}

        writeApplications(versionId, jGenerator);

		jGenerator.writeEndObject(); // }
		jGenerator.close();

		HttpPost post = new HttpPost(mmcPath + "/deployments");
        StringEntity sre = new StringEntity(stringWriter.toString(), ContentType.create("application/json", "UTF-8"));
		logger.finer(".... restfullyCreateDeployment request" + stringWriter.toString() );
		post.setEntity(sre);
        CloseableHttpClient httpClient = getHttpClient();
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, post, context)) {
            processResponseCode(response);

            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            String id = jsonNode.path("id").asText();
            logger.fine("<<<< restfullyCreateDeployment => " + id);
            return id;
        }
	}

    private void writeClusters(String clusterId, JsonGenerator jGenerator) throws IOException {
        jGenerator.writeFieldName("clusters"); // "clusters" :
        jGenerator.writeStartArray(); // [
        jGenerator.writeString(clusterId); // "clusterId"
        jGenerator.writeEndArray(); // ]
    }

    private void writeServers(String serverId, JsonGenerator jGenerator) throws IOException {
        HashSet<String> serverIds = new HashSet<>();
        serverIds.add(serverId);
        writeServers(serverIds, jGenerator);
    }

    private void writeServers(Set<String> serverIds, JsonGenerator jGenerator) throws IOException {
        jGenerator.writeFieldName("servers"); // "servers" :
        jGenerator.writeStartArray(); // [
        for (String serverId : serverIds)
        {
            jGenerator.writeString(serverId); // "serverId"
        }
        jGenerator.writeEndArray(); // ]
    }

    private void writeName(String deploymentName, JsonGenerator jGenerator) throws IOException {
        jGenerator.writeStringField("name", deploymentName); // "name" : name
    }

    /**
     * Undeploy an application from a target
	 * All deployments are searched for where any version of the application is deployed to a target, and those deployments are
	 * undeployed (and deleted).
     * @param applicationName name of the application
     * @param targetName name of the target, either a server, a server group or a cluster
     * @throws Exception json handling failed
     */
    public void undeploy(String applicationName, String targetName) throws Exception {
        logger.info(">>>> undeploy " + applicationName + " from " + targetName);

        Set<String> deploymentIds = getDeployedDeployments(applicationName, targetName);

        for (String deploymentId : deploymentIds) {
            restfullyUndeployById(deploymentId);
        }
        logger.info("<<<< undeploy finished");
    }

    private void restfullyUndeployById(String deploymentId) throws Exception {
        logger.fine(">>>> restfullyUndeployById " + deploymentId);

        CloseableHttpClient httpClient = getHttpClient();
        HttpPost post = new HttpPost(mmcPath + "/deployments/" + deploymentId + "/undeploy");
        CloseableHttpResponse response = httpClient.execute(mmcHost, post, context);
        processResponseCode(response);
        logger.fine("<<<< restfullyUndeployById finished");
    }

    private Set<String> getDeployedDeployments(String applicationName, String targetName) throws Exception {

        return getDeployments(applicationName, targetName, true);
    }

    public Set<String> getAllDeployments(String applicationName, String targetName) throws Exception {

        return getDeployments(applicationName, targetName, false);
    }

    private Set<String> getDeployments(String applicationName, String targetName, boolean deployedOnly) throws Exception {

        final String applicationId = restfullyGetApplicationId(applicationName);
        final HashSet<String> versionIds = restfullyGetVersionIds(applicationId);

        Set<String> deploymentIds = new HashSet<>();

        final String serverId = restfullyGetServerId(targetName);
        if (serverId != null) {
			deploymentIds.addAll(restfullyGetDeployedDeployments(versionIds, serverId, SERVER_TYPE.SERVER, deployedOnly));
		} else {
			final String serverGroupId = restfullyGetServerGroupId(targetName);
			if (serverGroupId != null) {
				deploymentIds.addAll(restfullyGetDeployedDeployments(versionIds, serverGroupId, SERVER_TYPE.GROUP, deployedOnly));
                final Set<String> clusterIds = restfullyGetClustersOfGroupId(serverGroupId);
                for (String clusterId : clusterIds) {
                    deploymentIds.addAll(restfullyGetDeployedDeployments(versionIds, clusterId, SERVER_TYPE.CLUSTER, deployedOnly));
                }
            } else {
				final String clusterId = restfullyGetClusterId(targetName);
				if (clusterId != null)
					deploymentIds.addAll(restfullyGetDeployedDeployments(versionIds, clusterId, SERVER_TYPE.CLUSTER, deployedOnly));
			}
		}
        return deploymentIds;
    }

    public Set<String> getAllDeployments(HashSet<String> versionIds, String targetId, SERVER_TYPE serverType) throws Exception {
        return restfullyGetDeployedDeployments(versionIds, targetId, serverType, false);
    }

    public Set<String> getDeployedDeployments(HashSet<String> versionIds, String targetId, SERVER_TYPE serverType) throws Exception {
        return restfullyGetDeployedDeployments(versionIds, targetId, serverType, true);
    }

    public Set<String> restfullyGetClustersOfGroupId(String groupId) throws Exception {
        logger.fine(">>>> restfullyGetClustersOfGroupId " + groupId);

        CloseableHttpClient httpClient = getHttpClient();
        HttpGet get = new HttpGet(mmcPath + "/clusters");
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            String string = EntityUtils.toString(response.getEntity());
            JsonNode jsonNode = OBJECT_MAPPER.readTree(string);

            Set<String> clusterIds = new HashSet<String>();
            ArrayNode clusters = (ArrayNode) jsonNode.path("data");
            for (JsonNode clusterNode : clusters) {
                ArrayNode groupIds = (ArrayNode) clusterNode.path("groupIds");
                for (JsonNode groupNode : groupIds) {
                    if (groupNode.asText().equals(groupId))
                        clusterIds.add(clusterNode.path("id").asText());
                }
            }
            logger.fine("<<<< restfullyGetClustersOfGroupId => " + clusterIds);
            return clusterIds;
        }
    }

    private Set<String> restfullyGetDeployedDeployments(HashSet<String> versionIds, String targetId, SERVER_TYPE serverType, boolean deployedOnly) throws Exception {
		logger.fine(">>>> restfullyGetDeployedDeployments " + versionIds + " " + targetId + " " + serverType.queryString + " " + deployedOnly);

        CloseableHttpClient httpClient = getHttpClient();
        HttpGet get = new HttpGet(mmcPath + "/deployments?" + serverType.queryString + "=" + targetId);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            HttpEntity entity = response.getEntity();
            if (entity != null) {
                entity = new BufferedHttpEntity(entity);
            }
            logger.finer(">>>> restfullyGetDeployedDeployments response " + EntityUtils.toString(entity));

            InputStream responseStream = entity.getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            JsonNode deploymentsNode = jsonNode.path("data");

            HashSet<String> deploymentIds = new HashSet<>();
            for (JsonNode deploymentNode : deploymentsNode) {
                if (deployedOnly && !STATUS_DEPLOYED.equals(deploymentNode.path("status").asText())) {
                    continue;
                }

                ArrayNode deployedVersionIds = (ArrayNode) deploymentNode.path("applications");
                for (JsonNode versionNode : deployedVersionIds) {
                    String versionId = versionNode.asText();
                    if (versionIds.contains(versionId)) {
                        deploymentIds.add(deploymentNode.path("id").asText());
                        break;
                    }
                }
            }
            logger.fine("<<<< restfullyGetDeployedDeployments => " + deploymentIds);

            return deploymentIds;
        }
    }

    /** get all versionIds belonging to an application
     *
     * @param applicationId id of the application
     * @return a set of version-ids known to the MMC
     */
    private HashSet<String> restfullyGetVersionIds(String applicationId) throws Exception {
        logger.fine(">>>> restfullyGetVersionIds " + applicationId);

        HashSet<String> versions = new HashSet<>();

        CloseableHttpClient httpClient = getHttpClient();
        HttpGet get = new HttpGet(mmcPath + "/repository/" + applicationId);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            JsonNode versionsNode = jsonNode.path("data");
            for (JsonNode versionNode : versionsNode) {
                versions.add(versionNode.path("id").asText());
            }
            logger.fine("<<<< restfullyGetVersionIds => " + versions);
            return versions;
        }
    }

    public void deleteDeployment(String name) throws Exception
	{
		logger.info(">>>> deleteDeployment " + name);

		String deploymentId = restfullyGetDeploymentId(name);
		if (deploymentId != null)
		{
			restfullyDeleteDeploymentById(deploymentId);
		}
        logger.info("<<<< deleteDeployment finished");
	}

	public void restfullyDeleteDeploymentById(String deploymentId) throws Exception
	{
		logger.info(">>>> restfullyDeleteDeploymentById " + deploymentId);

		CloseableHttpClient httpClient = getHttpClient();
		HttpDelete delete = new HttpDelete(mmcPath + "/deployments/" + deploymentId);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, delete, context)) {
            processResponseCode(response);
        }
        logger.info("<<<< restfullyDeleteDeploymentById finished");
	}

	public void restfullyDeployDeploymentById(String deploymentId) throws Exception
	{
		logger.fine(">>>> restfullyDeployDeploymentById " + deploymentId);

        CloseableHttpClient httpClient = getHttpClient();
		HttpPost post = new HttpPost(mmcPath + "/deployments/" + deploymentId+ "/deploy");
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, post, context)) {
            processResponseCode(response);
        }
        logger.fine("<<<< restfullyDeployDeploymentById finished");
	}

	public String restfullyGetDeploymentStatus(String deploymentId) throws Exception {
		logger.fine(">>>> restfullyGetDeploymentStatus " + deploymentId);

        CloseableHttpClient httpClient = getHttpClient();
		HttpGet get = new HttpGet(mmcPath + "/deployments/" + deploymentId);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            String status = "DELETED";
            if (response.getStatusLine().getStatusCode() != Status.NOT_FOUND.getStatusCode()) {
                processResponseCode(response);
                InputStream responseStream = response.getEntity().getContent();
                JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
                status = jsonNode.path(STATUS_FIELD_NAME).asText();
            }
            logger.fine("<<<< restfullyGetDeploymentStatus => " + status);
            return status;
        }
	}

	private String restfullyGetDeploymentId(String name) throws Exception
	{
		logger.fine(">>>> restfullyGetDeploymentId " + name);

		CloseableHttpClient httpClient = getHttpClient();
		HttpGet get = new HttpGet(mmcPath + "/deployments");
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            String deploymentId = null;
            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            JsonNode deploymentsNode = jsonNode.path(DATA_FIELD_NAME);
            for (JsonNode deploymentNode : deploymentsNode) {
                if (name.equals(deploymentNode.path(NAME_FIELD_NAME).asText())) {
                    deploymentId = deploymentNode.path(ID_FIELD_NAME).asText();
                    break;
                }
            }
            logger.fine("<<<< restfullyGetDeploymentId => " + deploymentId);
            return deploymentId;
        }
	}

	private String restfullyGetVersionId(String name, String version) throws Exception
	{
		logger.fine(">>>> restfullyGetVersionId " + name + " " + version);

		CloseableHttpClient httpClient = getHttpClient();
		HttpGet get = new HttpGet(mmcPath + "/repository");
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            String versionId = null;
            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            JsonNode applicationsNode = jsonNode.path(DATA_FIELD_NAME);
            applications:
            for (JsonNode applicationNode : applicationsNode) {
                if (name.equals(applicationNode.path(NAME_FIELD_NAME).asText())) {
                    JsonNode versionsNode = applicationNode.path(VERSIONS_FIELD_NAME);
                    for (JsonNode versionNode : versionsNode) {
                        if (version.equals(versionNode.path(NAME_FIELD_NAME).asText())) {
                            versionId = versionNode.get(ID_FIELD_NAME).asText();
                            break applications;
                        }
                    }
                }
            }
            logger.fine("<<<< restfullyGetVersionId => " + versionId);
            return versionId;
        }
	}

    private String restfullyGetApplicationId(String name) throws Exception
    {
        logger.fine(">>>> restfullyGetApplicationId " + name);

        CloseableHttpClient httpClient = getHttpClient();
        HttpGet get = new HttpGet(mmcPath + "/repository");
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            String applicationId = null;
            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            JsonNode applicationsNode = jsonNode.path(DATA_FIELD_NAME);
            for (JsonNode applicationNode : applicationsNode) {
                if (name.equals(applicationNode.path(NAME_FIELD_NAME).asText())) {
                    applicationId = applicationNode.get(ID_FIELD_NAME).asText();
                    break;
                }
            }
            logger.fine("<<<< restfullyGetApplicationId => " + applicationId);
            return applicationId;
        }
    }

    private String restfullyGetServerId(String name) throws Exception {
        logger.fine(">>>> restfullyGetServerId " + name);

        CloseableHttpClient httpClient = getHttpClient();
        HttpGet get = new HttpGet(mmcPath + "/servers?name=" + name);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            String serverId = null;
            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            JsonNode serversNode = jsonNode.path(DATA_FIELD_NAME);
            for (JsonNode serverNode : serversNode) {
                if (name.equals(serverNode.path(NAME_FIELD_NAME).asText())) {
                    serverId = serverNode.path(ID_FIELD_NAME).asText();
                    break;
                }
            }
            logger.fine("<<<< restfullyGetServerId => " + serverId);
            return serverId;
        }
    }

    /** Get the id of a serverGroup
     *
     * @param name name of the group to look up
     * @return id of the group or null if no such group exists
     */
    private String restfullyGetServerGroupId(String name) throws Exception {
        logger.fine(">>>> restfullyGetServerGroupId " + name);

        CloseableHttpClient httpClient = getHttpClient();
        HttpGet get = new HttpGet(mmcPath + "/serverGroups?name=" + name);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            String serverGroupId = null;
            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            JsonNode groupsNode = jsonNode.path(DATA_FIELD_NAME);
            for (JsonNode groupNode : groupsNode) {
                if (name.equals(groupNode.path(NAME_FIELD_NAME).asText())) {
                    serverGroupId = groupNode.path(ID_FIELD_NAME).asText();
                    break;
                }
            }
            logger.fine("<<<< restfullyGetServerGroupId => " + serverGroupId);
            return serverGroupId;
        }
    }


	public String restfullyUploadRepository(String name, String version, File packageFile) throws Exception
	{
		logger.info(">>>> restfullyUploadRepository " + name + " " + version + " " + packageFile);

        CloseableHttpClient httpClient = getHttpClient();
		HttpPost post = new HttpPost(mmcPath + "/repository");
        ContentType plainAsciiContentType = ContentType.create("text/plain", Consts.ASCII);
        HttpEntity multipartEntity = MultipartEntityBuilder.create()
                .setMode(HttpMultipartMode.BROWSER_COMPATIBLE)
                .addPart("file", new FileBody(packageFile))
                .addPart("name", new StringBody(name, plainAsciiContentType))
                .addPart("version", new StringBody(version, plainAsciiContentType))
                .build();
		post.setEntity(multipartEntity);

        try (CloseableHttpResponse response = httpClient.execute(mmcHost, post, context)) {
            //in the case of a conflict status code, use the pre-existing application
            String versionId = null;
            if (response.getStatusLine().getStatusCode() != Status.CONFLICT.getStatusCode()) {
                processResponseCode(response);
                String responseObject = EntityUtils.toString(response.getEntity());
                ObjectMapper mapper = new ObjectMapper();
                JsonNode result = mapper.readTree(responseObject);
                versionId = result.path("versionId").asText();
            } else {
                // TODO: remove handling of conflict and instead check for existence at caller
                logger.info("ARTIFACT " + name + " " + version + " ALREADY EXISTS in MMC. Creating Deployment using Pre-Existing Artifact (Not-Overwriting)");
                versionId = restfullyGetVersionId(name, version);
            }
            logger.fine("<<<< restfullyUploadRepository => " + versionId);
            return versionId;
        }
	}

	private void restfullyDeleteApplicationById(String applicationVersionId) throws Exception
	{
		logger.fine(">>>> restfullyDeleteApplicationById " + applicationVersionId);

		CloseableHttpClient httpClient = getHttpClient();
		HttpDelete delete = new HttpDelete(mmcPath + "/repository/" + applicationVersionId);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, delete, context)) {
            processResponseCode(response);
        }
        logger.fine("<<<< restfullyDeleteApplicationById finished");
	}

	public void deleteApplication(String applicationName, String version) throws Exception
	{
		logger.info(">>>> deleteApplication " + applicationName + " " + version);

		String applicationVersionId = restfullyGetVersionId(applicationName, version);
		if (applicationVersionId != null)
		{
			restfullyDeleteApplicationById(applicationVersionId);
		}
        logger.info("<<<< deleteApplication finished");
	}

    private void writeApplications(String versionId, JsonGenerator jGenerator) throws IOException {
        jGenerator.writeFieldName("applications"); // "applications" :
        jGenerator.writeStartArray(); // [
        jGenerator.writeString(versionId); // "applicationId"
        jGenerator.writeEndArray(); // ]
    }

    public String restfullyGetClusterId(String clusterName) throws Exception
	{
		logger.fine(">>>> restfullyGetClusterId " + clusterName);

		CloseableHttpClient httpClient = getHttpClient();
		HttpGet get = new HttpGet(mmcPath + "/clusters");
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            String string = EntityUtils.toString(response.getEntity());
            logger.fine(">>>> restfullyGetClusterId response " + string);
            JsonNode jsonNode = OBJECT_MAPPER.readTree(string);

            String clusterId = null;
            for (JsonNode node : jsonNode.path(DATA_FIELD_NAME)) {
                if (node.path(NAME_FIELD_NAME).asText().equals(clusterName)) {
                    clusterId = node.path(ID_FIELD_NAME).asText();
                    break;
                }
            }
            logger.fine("<<<< restfullyGetClusterId => " + clusterId);
            return clusterId;
        }
	}

    private CloseableHttpClient getHttpClient() {
        return mmcHttpClient;
    }
}