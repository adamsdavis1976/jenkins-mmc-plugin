package org.jenkinsci.plugins.mulemmc;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.http.Consts;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
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

	private static final String SNAPSHOT = "SNAPSHOT";
	public static final String STATUS_DEPLOYED = "DEPLOYED";

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private HttpContext context;
    private HttpHost mmcHost;
    private String mmcPath;

    public MuleRest(URL mmcUrl, CloseableHttpClient httpClient, HttpContext context) {
		this.mmcHost = new HttpHost(mmcUrl.getHost(), mmcUrl.getPort());
		this.mmcPath = mmcUrl.getPath();
        this.mmcHttpClient = httpClient;
		this.context = context;
		logger.finer("MMC URL: " + mmcUrl);
	}
	
	private void processResponseCode(HttpResponse response) throws Exception
	{
	    int code = response.getStatusLine().getStatusCode();
		logger.finer(">>>> processResponseCode " + code);

        Exception e = null;

        if (code == Status.NOT_FOUND.getStatusCode())
		    e = new Exception("The resource was not found.");
		else if (code == Status.CONFLICT.getStatusCode())
	        e = new Exception("The operation was unsuccessful because a resource with that name already exists.");
		else if (code == Status.INTERNAL_SERVER_ERROR.getStatusCode())
		    e = new Exception("The operation was unsuccessful.");
		else if (code != Status.OK.getStatusCode())
		    e = new Exception("Unexpected Status Code Return, Status Line: " + code);

        if (e != null)
            throw e;
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
		logger.fine(">>>> restfullyCreateDeployment request" + stringWriter.toString() );
		post.setEntity(sre);

        CloseableHttpClient httpClient = getHttpClient();
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, post, context)) {
            processResponseCode(response);

            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            String id = jsonNode.path("id").asText();
            logger.fine(">>>> restfullyCreateDeployment created id " + id);
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
    }

    public void deleteDeployments(String applicationName, String targetName) throws Exception {
        logger.info(">>>> delete deployments with " + applicationName + " on " + targetName);

        Set<String> deploymentIds = getAllDeployments(applicationName, targetName);

        for (String deploymentId : deploymentIds) {
            restfullyDeleteDeploymentById(deploymentId);
        }
    }

    private void restfullyUndeployById(String deploymentId) throws Exception {
        logger.info(">>>> restfullyUndeployById " + deploymentId);

        CloseableHttpClient httpClient = getHttpClient();
        HttpPost post = new HttpPost(mmcPath + "/deployments/" + deploymentId + "/undeploy");
        CloseableHttpResponse response = httpClient.execute(mmcHost, post, context);
        processResponseCode(response);
    }

    private Set<String> getDeployedDeployments(String applicationName, String targetName) throws Exception {

        return getDeployments(applicationName, targetName, true);
    }

    private Set<String> getAllDeployments(String applicationName, String targetName) throws Exception {

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

	/** Check whether a deployment is deployed.
	 * @param deploymentId id of the deployment
     * @return check whether a deployment is actually deployed
     * @throws Exception json handling failed
	 */
	protected boolean isDeployed(String deploymentId) throws Exception {
        CloseableHttpClient httpClient = getHttpClient();
		HttpGet get = new HttpGet(mmcPath + "/deployments/" + deploymentId);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            HttpEntity entity = response.getEntity();
            if (entity != null) {
                entity = new BufferedHttpEntity(entity);
            }
            logger.finer(">>>> restfullyGetDeployedDeployments response " + EntityUtils.toString(entity));

            InputStream responseStream = entity.getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            String status = jsonNode.path("data[0].status").asText();
            return STATUS_DEPLOYED.equals(status);
        }
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
            logger.fine(">>>> restfullyGetClustersOfGroupId => " + clusterIds);
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
            logger.fine(">>>> restfullyGetDeployedDeployments returns " + deploymentIds);

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
            logger.fine(">>>> restfullyGetVersionIds => " + versions);
            return versions;
        }
    }

    public void restfullyDeleteDeployment(String name) throws Exception
	{
		logger.info(">>>> restfullyDeleteDeployment " + name);

		String deploymentId = restfullyGetDeploymentId(name);
		if (deploymentId != null)
		{
			restfullyDeleteDeploymentById(deploymentId);
		}
		
	}

	private void restfullyDeleteDeploymentById(String deploymentId) throws Exception
	{
		logger.info(">>>> restfullyDeleteDeploymentById " + deploymentId);

		CloseableHttpClient httpClient = getHttpClient();
		HttpDelete delete = new HttpDelete(mmcPath + "/deployments/" + deploymentId);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, delete, context)) {
            processResponseCode(response);
        }
	}

	public void restfullyDeployDeploymentById(String deploymentId) throws Exception
	{
		logger.info(">>>> restfullyDeployDeploymentById " + deploymentId);


        CloseableHttpClient httpClient = getHttpClient();
		HttpPost post = new HttpPost(mmcPath + "/deployments/" + deploymentId+ "/deploy");
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, post, context)) {
            processResponseCode(response);
        }
	}

	public String restfullyWaitStartupForCompletion(String deploymentId) throws Exception {
		logger.fine(">>>>restfullyWaitStartupForCompletion " + deploymentId);

        CloseableHttpClient httpClient = getHttpClient();
		HttpGet get = new HttpGet(mmcPath + "/deployments/" + deploymentId);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);
            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            String status = jsonNode.path("status").asText();
            logger.fine(">>>> restfullyCreateDeployment status " + status);
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
            JsonNode deploymentsNode = jsonNode.path("data");
            for (JsonNode deploymentNode : deploymentsNode) {
                if (name.equals(deploymentNode.path("name").asText())) {
                    deploymentId = deploymentNode.path("id").asText();
                    break;
                }
            }
            logger.fine(">>>> restfullyGetDeploymentId => " + deploymentId);
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

            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            JsonNode applicationsNode = jsonNode.path("data");
            for (JsonNode applicationNode : applicationsNode) {
                if (name.equals(applicationNode.path("name").asText())) {
                    JsonNode versionsNode = applicationNode.path("versions");
                    for (JsonNode versionNode : versionsNode) {
                        if (version.equals(versionNode.path("name").asText())) {
                            String versionId = versionNode.get("id").asText();
                            logger.fine(">>>> restfullyGetVersionId => " + versionId);
                            return versionId;
                        }
                    }
                }
            }
            return null;
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
            JsonNode applicationsNode = jsonNode.path("data");
            for (JsonNode applicationNode : applicationsNode) {
                if (name.equals(applicationNode.path("name").asText())) {
                    applicationId = applicationNode.get("id").asText();
                    break;
                }
            }
            logger.fine(">>>> restfullyGetApplicationId => " + applicationId);
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
            JsonNode serversNode = jsonNode.path("data");
            for (JsonNode serverNode : serversNode) {
                if (name.equals(serverNode.path("name").asText())) {
                    serverId = serverNode.path("id").asText();
                    break;
                }
            }
            logger.fine(">>>> restfullyGetServerId => " + serverId);
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
        HttpGet get = new HttpGet(mmcPath + "/serverGroups");
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, get, context)) {
            processResponseCode(response);

            String serverGroupId = null;
            InputStream responseStream = response.getEntity().getContent();
            JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
            JsonNode groupsNode = jsonNode.path("data");
            for (JsonNode groupNode : groupsNode) {
                if (name.equals(groupNode.path("name").asText())) {
                    serverGroupId = groupNode.path("id").asText();
                    break;
                }
            }
            logger.fine(">>>> restfullyGetServerGroupId => " + serverGroupId);
            return serverGroupId;
        }
    }


	public String restfullyUploadRepository(String name, String version, File packageFile) throws Exception
	{
		logger.info(">>>> restfullyUploadRepository " + name + " " + version + " " + packageFile);

		// delete application first
		if (isSnapshotVersion(version))
		{
			logger.fine("Is Snapshot. Delete " + name + " " + version);
			restfullyDeleteApplication(name, version);
		}

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
            if (response.getStatusLine().getStatusCode() != Status.CONFLICT.getStatusCode()) {
                processResponseCode(response);
            } else {
                logger.info("ARTIFACT " + name + " " + version + " ALREADY EXISTS in MMC. Creating Deployment using Pre-Existing Artifact (Not-Overwriting)");
                return restfullyGetVersionId(name, version);
            }

            String responseObject = EntityUtils.toString(response.getEntity());
            ObjectMapper mapper = new ObjectMapper();
            JsonNode result = mapper.readTree(responseObject);
            String versionId = result.path("versionId").asText();
            logger.fine(">>>> restfullyUploadRepository => " + versionId);
            return versionId;
        }
	}

	private void restfullyDeleteApplicationById(String applicationVersionId) throws Exception
	{
		logger.info(">>>> restfullyDeleteApplicationById " + applicationVersionId);

		CloseableHttpClient httpClient = getHttpClient();
		HttpDelete delete = new HttpDelete(mmcPath + "/repository/" + applicationVersionId);
        try (CloseableHttpResponse response = httpClient.execute(mmcHost, delete, context)) {
            processResponseCode(response);
        }
	}

	private void restfullyDeleteApplication(String applicationName, String version) throws Exception
	{
		logger.info(">>>> restfullyDeleteApplication " + applicationName + " " + version);

		String applicationVersionId = restfullyGetVersionId(applicationName, version);
		if (applicationVersionId != null)
		{
			restfullyDeleteApplicationById(applicationVersionId);
		}
	}

	private boolean isSnapshotVersion(String version)
	{
		return version.contains(SNAPSHOT);
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

            for (JsonNode node : jsonNode.path("data")) {
                if (node.path("name").asText().equals(clusterName))
                    return node.path("id").asText();
            }

            logger.fine(">>>> restfullyGetClusterId - no matching cluster retreived from MMC");
        }
        return null;
	}

	private CloseableHttpClient mmcHttpClient = null;

	private CloseableHttpClient getHttpClient() {
	    return mmcHttpClient;
    }
}