package org.jenkinsci.plugins.mulemmc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import javax.ws.rs.core.Response.Status;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.HttpException;
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
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.ObjectMapper;

import java.util.logging.Logger;

public class MuleRest
{
	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final Logger logger = Logger.getLogger(MuleRest.class.getName());
	private static final String SNAPSHOT = "SNAPSHOT";

	private URL mmcUrl;
	private String username;
	private String password;

	public MuleRest(URL mmcUrl, String username, String password) {
		this.mmcUrl = mmcUrl;
		this.username = username;
		this.password = password;
		logger.fine("MMC URL: {}, Username: {}" + " " + mmcUrl + " " + username);

	}
	
	private void processResponseCode(int code) throws Exception
	{
		logger.fine(">>>>processResponseCode " + code);

		if (code == Status.OK.getStatusCode())
		{
			// ok
		} else if (code == Status.NOT_FOUND.getStatusCode())
		{
			Exception he = new Exception("The resource was not found.");
			throw he;
		} else if (code == Status.CONFLICT.getStatusCode())
		{
			Exception he = new Exception("The operation was unsuccessful because a resource with that name already exists.");
			throw he;
		} else if (code == Status.INTERNAL_SERVER_ERROR.getStatusCode())
		{
			Exception he = new Exception("The operation was unsuccessful.");
			throw he;
		} else
		{
			Exception he = new Exception("Unexpected Status Code Return, Status Line: " + code);
			throw he;
		}
	}

	public String restfullyCreateDeployment(String serverGroup, String name, String versionId) throws Exception
	{
		logger.fine(">>>>restfullyCreateDeployment " + serverGroup + " " + name + " " + versionId);

		Set<String> serversIds = restfullyGetServers(serverGroup);
		if (serversIds.isEmpty()) { throw new IllegalArgumentException("No server found into group : " + serverGroup); }

		// delete existing deployment before creating new one
		restfullyDeleteDeployment(name);

		HttpClient httpClient = configureHttpClient();

		StringWriter stringWriter = new StringWriter();
		JsonFactory jfactory = new JsonFactory();
		JsonGenerator jGenerator = jfactory.createJsonGenerator(stringWriter);
		jGenerator.writeStartObject(); // {
		jGenerator.writeStringField("name", name); // "name" : name
		jGenerator.writeFieldName("servers"); // "servers" :
		jGenerator.writeStartArray(); // [
		for (String serverId : serversIds)
		{
			jGenerator.writeString(serverId); // "serverId"
		}
		jGenerator.writeEndArray(); // ]
		jGenerator.writeFieldName("applications"); // "applications" :
		jGenerator.writeStartArray(); // [
		jGenerator.writeString(versionId); // "applicationId"
		jGenerator.writeEndArray(); // ]
		jGenerator.writeEndObject(); // }
		jGenerator.close();

		PostMethod post = new PostMethod(mmcUrl + "/deployments");
		post.setDoAuthentication(true);
		StringRequestEntity sre = new StringRequestEntity(stringWriter.toString(), "application/json", null);
		logger.fine(">>>>restfullyCreateDeployment request" + stringWriter.toString() );
		
		post.setRequestEntity(sre);

		int statusCode = httpClient.executeMethod(post);

		if (statusCode!=200)  
			logger.fine(">>>>restfullyCreateDeployment error response "+post.getResponseBodyAsString());
		
		processResponseCode(statusCode);
		
		InputStream responseStream = post.getResponseBodyAsStream();
		
		
		JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);

		String id = jsonNode.path("id").asText();
		
		logger.fine(">>>>restfullyCreateDeployment created id " + id );
		
		return id;

	}

	public void restfullyDeleteDeployment(String name) throws Exception
	{
		logger.fine(">>>>restfullyDeleteDeployment " + name);

		String deploymentId = restfullyGetDeploymentIdByName(name);
		if (deploymentId != null)
		{
			restfullyDeleteDeploymentById(deploymentId);
		}
		
	}

	public void restfullyDeleteDeploymentById(String deploymentId) throws Exception
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
			logger.fine(">>>>restfullyCreateDeployment error response "+get.getResponseBodyAsString());
		
		processResponseCode(statusCode);

		InputStream responseStream = get.getResponseBodyAsStream();
		JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
		String status = jsonNode.path("status").asText();
		logger.fine(">>>>restfullyCreateDeployment status "+ status);
		return status;
	}

	public String restfullyGetDeploymentIdByName(String name) throws Exception 
	{
		logger.fine(">>>>restfullyGetDeploymentIdByName " + name);

		HttpClient httpClient = configureHttpClient();

		GetMethod get = new GetMethod(mmcUrl + "/deployments");

		int statusCode = httpClient.executeMethod(get);

		processResponseCode(statusCode);

		InputStream responseStream = get.getResponseBodyAsStream();
		JsonNode jsonNode = OBJECT_MAPPER.readTree(responseStream);
		JsonNode deploymentsNode = jsonNode.path("data");
		for (JsonNode deploymentNode : deploymentsNode)
		{
			if (name.equals(deploymentNode.path("name").asText())) { return deploymentNode.path("id").asText();

			}
		}
		return null;
	}

	public String restfullyGetApplicationId(String name, String version) throws Exception
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
					if (version.equals(versionNode.path("name").asText())) { return versionNode.get("id").asText(); }
				}
			}
		}

		return null;
	}

	public final String restfullyGetServerGroupId(String serverGroup) throws Exception
	{
		logger.fine(">>>>restfullyGetServerGroupId " + serverGroup);

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
			if (serverGroup.equals(groupNode.path("name").asText()))
			{
				serverGroupId = groupNode.path("id").asText();
			}
		}

		if (serverGroupId == null) { throw new IllegalArgumentException("no server group found having the name " + serverGroup); }

		return serverGroupId;
	}

	public Set<String> restfullyGetServers(String serverGroup) throws Exception
	{
		logger.fine(">>>>restfullyGetServers " + serverGroup);

		HttpClient httpClient = configureHttpClient();

		GetMethod get = new GetMethod(mmcUrl + "/servers");

		int statusCode = httpClient.executeMethod(get);

		Set<String> serversId = new TreeSet<String>();
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

		ObjectMapper mapper = new ObjectMapper();
		JsonNode result = mapper.readTree(responseObject);
		return result.path("versionId").asText();

	}

	public void restfullyDeleteApplicationById(String applicationVersionId) throws Exception
	{
		logger.fine(">>>>restfullyDeleteApplicationById " + applicationVersionId);

		HttpClient httpClient = configureHttpClient();

		DeleteMethod delete = new DeleteMethod(mmcUrl + "/repository/" + applicationVersionId);

		int statusCode = httpClient.executeMethod(delete);

		processResponseCode(statusCode);

	}

	public void restfullyDeleteApplication(String applicationName, String version) throws Exception
	{
		logger.fine(">>>>restfullyDeleteApplication " + applicationName + "" + version);

		String applicationVersionId = restfullyGetApplicationId(applicationName, version);
		if (applicationVersionId != null)
		{
			restfullyDeleteApplicationById(applicationVersionId);
		}
	}

	protected boolean isSnapshotVersion(String version)
	{
		return version.contains(SNAPSHOT);
	}

	/**
	 * @param clusterId
	 * @param theName
	 * @param versionId
	 * @return
	 * @throws Exception
	 */
	public String restfullyCreateClusterDeployment(String clusterName, String name, String versionId) throws Exception
	{
		 logger.fine(">>>>restfullyCreateClusterDeployment  "+clusterName +" "+ name + " " + versionId);

			
		String clusterId = restfullyGetClusterId(clusterName);
		if (clusterId.isEmpty()) { 
			throw new IllegalArgumentException("Cluster not found : " + clusterName); 
		}

		restfullyDeleteDeployment(name);
		
		return restfullyCreateClusterDeploymentById(name, versionId, clusterId);

	}

	private String restfullyCreateClusterDeploymentById(String name, String versionId, String clusterId) throws Exception, IOException,
            JsonGenerationException, UnsupportedEncodingException, HttpException, JsonProcessingException
    {
	    logger.fine(">>>>restfullyCreateClusterDeploymentById  " + name + " " + versionId);

		HttpClient httpClient = configureHttpClient();

		StringWriter stringWriter = new StringWriter();
		JsonFactory jfactory = new JsonFactory();
		JsonGenerator jGenerator = jfactory.createJsonGenerator(stringWriter);
		jGenerator.writeStartObject(); // {
		jGenerator.writeStringField("name", name); // "name" : name
		jGenerator.writeFieldName("clusters"); // "clusters" :
		jGenerator.writeStartArray(); // [
		jGenerator.writeString(clusterId); // "clusterId"
		jGenerator.writeEndArray(); // ]
		jGenerator.writeFieldName("applications"); // "applications" :
		jGenerator.writeStartArray(); // [
		jGenerator.writeString(versionId); // "applicationId"
		jGenerator.writeEndArray(); // ]
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
				
		Iterator<JsonNode> nodeIt = jsonNode.path("data").getElements();
		while (nodeIt.hasNext()){
			JsonNode node = nodeIt.next();
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

			List<String> authPrefs = new ArrayList<String>(3);
			authPrefs.add(AuthPolicy.BASIC);
			mmcHttpClient.getParams().setParameter(AuthPolicy.AUTH_SCHEME_PRIORITY, authPrefs);
			mmcHttpClient.getParams().setAuthenticationPreemptive(true);
		}

		return mmcHttpClient;
	}

}