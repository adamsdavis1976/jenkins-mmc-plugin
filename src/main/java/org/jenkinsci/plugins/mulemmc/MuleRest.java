package org.jenkinsci.plugins.mulemmc;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthPolicy;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.DeleteMethod;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.StringRequestEntity;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.StringPart;

import javax.ws.rs.core.Response.Status;
import java.io.*;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

public class MuleRest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final Logger logger = Logger.getLogger(MuleRest.class.getName());
	private static final String SNAPSHOT = "SNAPSHOT";

	private URL mmcUrl;
	private String username;
	private String password;
    public static final JsonFactory JSON_FACTORY = new JsonFactory();

    public MuleRest(URL mmcUrl, String username, String password) {
		this.mmcUrl = mmcUrl;
		this.username = username;
		this.password = password;
		logger.fine("MMC URL: {}, Username: {}" + " " + mmcUrl + " " + username);
	}
	
	private void processResponseCode(int code) throws Exception
	{
		logger.fine(">>>>processResponseCode " + code);

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
     * @param applicationName name of the application to deploy
     * @param versionId id of the version-application to deploy
     * @return id of the generated deployment
     * @throws Exception
     */
	public String restfullyCreateDeployment(String deploymentName, String targetName, String applicationName, String versionId) throws Exception
	{
		logger.fine(">>>> restfullyCreateDeployment " + deploymentName + " " + targetName + " " + versionId);

        String serverGroupId = restfullyGetServerGroupId(targetName);
        //		Set<String> serverIds = restfullyGetServersOfServerGroup(targetName);
        //      String clusterId = restfullyGetClusterId(targetName);
		//      if (serverIds.isEmpty()) { throw new IllegalArgumentException("No server found in group : " + targetName); }

		// delete existing deployment before creating new one
		restfullyDeleteDeployment(deploymentName);
        restfullyUndeploy(applicationName, targetName);

		HttpClient httpClient = configureHttpClient();

		StringWriter stringWriter = new StringWriter();
        JsonGenerator jGenerator = JSON_FACTORY.createGenerator(stringWriter);
		jGenerator.writeStartObject(); // {

        writeName(deploymentName, jGenerator);

        writeServers(serverGroupId, jGenerator);
        writeApplications(versionId, jGenerator);

		jGenerator.writeEndObject(); // }
		jGenerator.close();

		PostMethod post = new PostMethod(mmcUrl + "/deployments");
		post.setDoAuthentication(true);
		StringRequestEntity sre = new StringRequestEntity(stringWriter.toString(), "application/json", null);
		logger.fine(">>>> restfullyCreateDeployment request" + stringWriter.toString() );
		
		post.setRequestEntity(sre);

		int statusCode = httpClient.executeMethod(post);

		if (statusCode!=200)  
			logger.fine(">>>> restfullyCreateDeployment error response " + post.getResponseBodyAsString());
		
		processResponseCode(statusCode);
		
		InputStream responseStream = post.getResponseBodyAsStream();
		
		JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);

		String id = jsonNode.path("id").asText();
		
		logger.fine(">>>> restfullyCreateDeployment created id " + id );
		
		return id;

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

    private void restfullyUndeploy(String applicationName, String serverGroup) throws Exception {
        logger.fine(">>>> restfullyUndeploy " + applicationName + " from " + serverGroup);

        Set<String> deploymentIds = findDeployments(applicationName, serverGroup);

        for (String deploymentId : deploymentIds) {
            restfullyUndeployById(deploymentId);
        }
    }

    private void restfullyUndeployById(String deploymentId) throws Exception {
        logger.fine(">>>> restfullyUndeployById " + deploymentId);

        HttpClient httpClient = configureHttpClient();
        PostMethod post = new PostMethod(mmcUrl + "/deployments/" + deploymentId + "/undeploy");

        int statusCode = httpClient.executeMethod(post);
        processResponseCode(statusCode);
    }

    private Set<String> findDeployments(String applicationName, String serverGroup) throws Exception {

        final String applicationId = restfullyGetApplicationId(applicationName);
        final HashSet<String> versionIds = restfullyGetVersionIds(applicationId);
        final String serverGroupId = restfullyGetServerGroupId(serverGroup);

        return restfullyGetDeployments(versionIds, serverGroupId);
    }

    private Set<String> restfullyGetDeployments(HashSet<String> versionIds, String serverGroupId) throws Exception {

        logger.fine(">>>> restfullyGetDeployments " + versionIds + " " + serverGroupId);

        HttpClient httpClient = configureHttpClient();
        GetMethod get = new GetMethod(mmcUrl + "/deployments?servers=" + serverGroupId);

        int statusCode = httpClient.executeMethod(get);

        processResponseCode(statusCode);

        logger.fine(">>>> restfullyGetDeployments response " + get.getResponseBodyAsString());

        InputStream responseStream = get.getResponseBodyAsStream();
        JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
        JsonNode deploymentsNode = jsonNode.path("data");

        HashSet<String> deploymentIds = new HashSet<>();
        for (JsonNode deploymentNode : deploymentsNode) {
            if (!"DEPLOYED".equals(deploymentNode.path("status").asText())) {
                logger.fine(">>>> restfullyGetDeployment ignoring " + deploymentNode.asText());
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
        logger.fine(">>>> restfullyGetDeployments returns " + deploymentIds);

        return deploymentIds;
    }

    /** get all versionIds belonging to an application
     *
     * @param applicationId id of the application
     * @return a set of version-ids known to the MMC
     * @throws Exception
     */
    private HashSet<String> restfullyGetVersionIds(String applicationId) throws Exception {
        logger.fine(">>>> restfullyGetVersionIds " + applicationId);

        HashSet<String> versions = new HashSet<>();

        HttpClient httpClient = configureHttpClient();
        GetMethod get = new GetMethod(mmcUrl + "/repository/" + applicationId);
        int statusCode = httpClient.executeMethod(get);

        processResponseCode(statusCode);

        InputStream responseStream = get.getResponseBodyAsStream();
        JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
        JsonNode versionsNode = jsonNode.path("data");
        for (JsonNode versionNode : versionsNode) {
            versions.add(versionNode.path("id").asText());
        }
        return versions;
    }

    private void restfullyDeleteDeployment(String name) throws Exception
	{
		logger.fine(">>>> restfullyDeleteDeployment " + name);

		String deploymentId = restfullyGetDeploymentId(name);
		if (deploymentId != null)
		{
			restfullyDeleteDeploymentById(deploymentId);
		}
		
	}

	private void restfullyDeleteDeploymentById(String deploymentId) throws Exception
	{
		logger.fine(">>>>restfullyDeleteDeploymentById " + deploymentId);

		HttpClient httpClient = configureHttpClient();

		DeleteMethod delete = new DeleteMethod(mmcUrl + "/deployments/" + deploymentId);

		int statusCode = httpClient.executeMethod(delete);

		processResponseCode(statusCode);

	}

	public void restfullyDeployDeploymentById(String deploymentId) throws Exception
	{
		logger.fine(">>>>restfullyDeployDeploymentById " + deploymentId);

		HttpClient httpClient = configureHttpClient();

		PostMethod post = new PostMethod(mmcUrl + "/deployments/" + deploymentId+ "/deploy");
		post.setDoAuthentication(true);

		int statusCode = httpClient.executeMethod(post);

		processResponseCode(statusCode);

	}
	

	public String restfullyWaitStartupForCompletion(String deploymentId) throws Exception {
		logger.fine(">>>>restfullyWaitStartupForCompletion " + deploymentId);

		HttpClient httpClient = configureHttpClient();

		GetMethod get = new GetMethod(mmcUrl + "/deployments/" + deploymentId);
		get.setDoAuthentication(true);

		int statusCode = httpClient.executeMethod(get);
		if (statusCode!=200)
			logger.fine(">>>>restfullyCreateDeployment error response " + get.getResponseBodyAsString());

		processResponseCode(statusCode);

		InputStream responseStream = get.getResponseBodyAsStream();
		JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
		String status = jsonNode.path("status").asText();
		logger.fine(">>>>restfullyCreateDeployment status "+ status);
		return status;
	}

	private String restfullyGetDeploymentId(String name) throws Exception
	{
		logger.fine(">>>>restfullyGetDeploymentId " + name);

		HttpClient httpClient = configureHttpClient();

		GetMethod get = new GetMethod(mmcUrl + "/deployments");

		int statusCode = httpClient.executeMethod(get);

		processResponseCode(statusCode);

		InputStream responseStream = get.getResponseBodyAsStream();
		JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
		JsonNode deploymentsNode = jsonNode.path("data");
		for (JsonNode deploymentNode : deploymentsNode)
		{
			if (name.equals(deploymentNode.path("name").asText())) {
                return deploymentNode.path("id").asText();
			}
		}
		return null;
	}

	private String restfullyGetApplicationId(String name, String version) throws Exception
	{
		logger.fine(">>>>restfullyGetApplicationId " + name + " " + version);

		HttpClient httpClient = configureHttpClient();

		GetMethod get = new GetMethod(mmcUrl + "/repository");

		int statusCode = httpClient.executeMethod(get);

		processResponseCode(statusCode);

		InputStream responseStream = get.getResponseBodyAsStream();
		JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
		JsonNode applicationsNode = jsonNode.path("data");
		for (JsonNode applicationNode : applicationsNode)
		{
			if (name.equals(applicationNode.path("name").asText()))
			{
				JsonNode versionsNode = applicationNode.path("versions");
				for (JsonNode versionNode : versionsNode)
				{
					if (version.equals(versionNode.path("name").asText())) {
                        return versionNode.get("id").asText();
					}
				}
			}
		}

		return null;
	}

    private String restfullyGetApplicationId(String name) throws Exception
    {
        logger.fine(">>>>restfullyGetApplicationId " + name);

        HttpClient httpClient = configureHttpClient();

        GetMethod get = new GetMethod(mmcUrl + "/repository");

        int statusCode = httpClient.executeMethod(get);

        processResponseCode(statusCode);

        InputStream responseStream = get.getResponseBodyAsStream();
        JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
        JsonNode applicationsNode = jsonNode.path("data");
        for (JsonNode applicationNode : applicationsNode)
        {
            if (name.equals(applicationNode.path("name").asText()))
            {
                return applicationNode.get("id").asText();
            }
        }

        return null;
    }

    /** Get the id of a serverGroup
     *
     * @param name name of the group to look up
     * @return id of the group or null if no such group exists
     * @throws Exception
     */
    private String restfullyCheckServerGroupId(String name) throws Exception {
        logger.fine(">>>>restfullyCheckServerGroupId " + name);

        HttpClient httpClient = configureHttpClient();

        GetMethod get = new GetMethod(mmcUrl + "/serverGroups");

        int statusCode = httpClient.executeMethod(get);

        String serverGroupId = null;

        processResponseCode(statusCode);

        InputStream responseStream = get.getResponseBodyAsStream();
        JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
        JsonNode groupsNode = jsonNode.path("data");
        for (JsonNode groupNode : groupsNode)
        {
            if (name.equals(groupNode.path("name").asText()))
            {
                serverGroupId = groupNode.path("id").asText();
            }
        }
        return serverGroupId;
    }

    private String restfullyGetServerGroupId(String serverGroup) throws Exception
	{
		logger.fine(">>>>restfullyGetServerGroupId " + serverGroup);

		String serverGroupId = restfullyCheckServerGroupId(serverGroup);
		if (serverGroupId == null) {
            throw new IllegalArgumentException("no server group found having the name " + serverGroup);
		}

		return serverGroupId;
	}

	private Set<String> restfullyGetServersOfServerGroup(String serverGroup) throws Exception
	{
		logger.fine(">>>>restfullyGetServersOfServerGroup " + serverGroup);

		HttpClient httpClient = configureHttpClient();

		GetMethod get = new GetMethod(mmcUrl + "/servers");

		int statusCode = httpClient.executeMethod(get);

		Set<String> serversId = new TreeSet<>();
		processResponseCode(statusCode);

		InputStream responseStream = get.getResponseBodyAsStream();
		JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
		JsonNode serversNode = jsonNode.path("data");
		for (JsonNode serverNode : serversNode)
		{
			String serverId = serverNode.path("id").asText();

			JsonNode groupsNode = serverNode.path("groups");
			for (JsonNode groupNode : groupsNode)
			{
				if (serverGroup.equals(groupNode.path("name").asText()))
				{
					serversId.add(serverId);
				}
			}
		}

		return serversId;
	}

	public String restfullyUploadRepository(String name, String version, File packageFile) throws Exception
	{
		logger.fine(">>>>restfullyUploadRepository " + name + " " + version + " " + packageFile);

		// delete application first
		if (isSnapshotVersion(version))
		{
			logger.fine("delete " + name + " " + version);
			restfullyDeleteApplication(name, version);
		}

		HttpClient httpClient = configureHttpClient();

		PostMethod post = new PostMethod(mmcUrl + "/repository");
		post.setDoAuthentication(true);

		Part[] parts = { new FilePart("file", packageFile), new StringPart("name", name),
		        new StringPart("version", version) };

		MultipartRequestEntity multipartEntity = new MultipartRequestEntity(parts, post.getParams());
		post.setRequestEntity(multipartEntity);

		int statusCode = httpClient.executeMethod(post);

		//in the case of a conflict status code, use the pre-existing application
		if (statusCode != Status.CONFLICT.getStatusCode()) {
			processResponseCode(statusCode);

		} else{
			logger.info("ARTIFACT ALREADY EXISTS in MMC. Creating Deployment using Pre-Existing Artifact (Not-Overwriting)");
			post.releaseConnection();
			return restfullyGetApplicationId(name, version);
		}

		String responseObject = post.getResponseBodyAsString();
		post.releaseConnection();
		post.releaseConnection();

		ObjectMapper mapper = new ObjectMapper();
		JsonNode result = mapper.readTree(responseObject);
		return result.path("versionId").asText();

	}

	private void restfullyDeleteApplicationById(String applicationVersionId) throws Exception
	{
		logger.fine(">>>>restfullyDeleteApplicationById " + applicationVersionId);

		HttpClient httpClient = configureHttpClient();

		DeleteMethod delete = new DeleteMethod(mmcUrl + "/repository/" + applicationVersionId);

		int statusCode = httpClient.executeMethod(delete);

		processResponseCode(statusCode);

	}

	private void restfullyDeleteApplication(String applicationName, String version) throws Exception
	{
		logger.fine(">>>>restfullyDeleteApplication " + applicationName + "" + version);

		String applicationVersionId = restfullyGetApplicationId(applicationName, version);
		if (applicationVersionId != null)
		{
			restfullyDeleteApplicationById(applicationVersionId);
		}
	}

	private boolean isSnapshotVersion(String version)
	{
		return version.contains(SNAPSHOT);
	}

	/**
	 * @param deploymentName name of the deployment
	 * @param clusterName name of cluster to deploy to
	 * @param applicationName name of the application to deploy to cluster
     * @param versionId versioned application id to deploy  @return the id of the created deployment
	 */
	public String restfullyCreateClusterDeployment(String deploymentName, String clusterName, String applicationName, String versionId) throws Exception
	{
        logger.fine(">>>>restfullyCreateClusterDeployment  "+clusterName +" "+ deploymentName + " " + versionId);
			
	    String clusterId = restfullyGetClusterId(clusterName);
		if (clusterId.isEmpty()) { 
			throw new IllegalArgumentException("Cluster not found : " + clusterName); 
		}

		restfullyDeleteDeployment(deploymentName);
		
		return restfullyCreateClusterDeploymentById(deploymentName, versionId, clusterId);
	}

	private String restfullyCreateClusterDeploymentById(String name, String versionId, String clusterId) throws Exception
    {
	    logger.fine(">>>>restfullyCreateClusterDeploymentById  " + name + " " + versionId);

		HttpClient httpClient = configureHttpClient();

		StringWriter stringWriter = new StringWriter();
		JsonGenerator jGenerator = JSON_FACTORY.createGenerator(stringWriter);
		jGenerator.writeStartObject(); // {

        writeName(name, jGenerator);
        writeClusters(clusterId, jGenerator);
        writeApplications(versionId, jGenerator);

        jGenerator.writeEndObject(); // }
		jGenerator.close();

		PostMethod post = new PostMethod(mmcUrl + "/deployments");
		post.setDoAuthentication(true);
		post.setRequestEntity(new StringRequestEntity(stringWriter.toString(), "application/json", null));

		logger.fine(">>>>restfullyCreateClusterDeploymentById request " + stringWriter.toString());

		int statusCode = httpClient.executeMethod(post);

		processResponseCode(statusCode);

		InputStream responseStream = post.getResponseBodyAsStream();

		JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
		return jsonNode.path("id").asText();
    }

    private void writeApplications(String versionId, JsonGenerator jGenerator) throws IOException {
        jGenerator.writeFieldName("applications"); // "applications" :
        jGenerator.writeStartArray(); // [
        jGenerator.writeString(versionId); // "applicationId"
        jGenerator.writeEndArray(); // ]
    }

    public String restfullyGetClusterId(String clusterName) throws Exception
	{

		logger.fine(">>>>restfullyGetClusterId " + clusterName);

		HttpClient httpClient = configureHttpClient();

		GetMethod get = new GetMethod(mmcUrl + "/clusters");

		int statusCode = httpClient.executeMethod(get);

		processResponseCode(statusCode);

		String string= get.getResponseBodyAsString();
		logger.fine(">>>>restfullyGetClusterId response " + string);
		JsonNode jsonNode = OBJECT_MAPPER.readTree(string);
				
		for (JsonNode node : jsonNode.path("data")) {
			if (node.path("name").asText().equals(clusterName))
				return node.path("id").asText();
		}
		
		logger.fine(">>>>restfullyGetClusterId - no matching cluster retreived from MMC");
		
		return null;

	}

	private HttpClient mmcHttpClient = null;

	private HttpClient configureHttpClient() throws Exception
	{
		if (mmcHttpClient == null)
		{

			mmcHttpClient = new HttpClient();

			mmcHttpClient.getState().setCredentials(new AuthScope(mmcUrl.getHost(), mmcUrl.getPort()),
			        new UsernamePasswordCredentials(username, password));

			List<String> authPrefs = new ArrayList<>(3);
			authPrefs.add(AuthPolicy.BASIC);
			mmcHttpClient.getParams().setParameter(AuthPolicy.AUTH_SCHEME_PRIORITY, authPrefs);
			mmcHttpClient.getParams().setAuthenticationPreemptive(true);
		}

		return mmcHttpClient;
	}

}