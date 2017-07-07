package org.jenkinsci.plugins.mulemmc;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.protocol.HttpCoreContext;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Set;
import java.util.concurrent.TimeoutException;

public class MMCDeployerBuilder extends Builder
{
	class StartupFailedException extends Exception {
        public StartupFailedException() {
        }
    }

    class DeletingFailedException extends Exception {
        public DeletingFailedException(final String deploymentId) {
            this.deploymentId = deploymentId;
        }
        public String toString() {
            return "deleting deployment " + deploymentId + " failed";
        }
        private String deploymentId;
    }

    private static final String SNAPSHOT = "SNAPSHOT";

    public static final String STATUS_IN_PROGRESS = "IN PROGRESS";
    public static final String STATUS_DELETING = "DELETING";
    public static final String STATUS_DELETED = "DELETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DEPLOYED = "DEPLOYED";
    public static final String STATUS_UNDEPLOYED = "UNDEPLOYED";

    public final String mmcUrl;
	public final String user;
	public final String password;
	public final String fileLocation;
	public final String artifactVersion;
	public final String artifactName;
	public final String deploymentName;
	public final boolean completeDeployment;
	public final String clusterOrServerGroupName;
	public final boolean deployWithPomDetails;
	public final String startupTimeout;
	public final boolean deleteOldDeployments;

	@DataBoundConstructor
	public MMCDeployerBuilder(String mmcUrl, String user, String password, boolean completeDeployment, String clusterOrServerGroupName,
	        String fileLocation, String artifactName, String deploymentName, String artifactVersion, boolean deleteOldDeployments, String startupTimeout) throws MalformedURLException {
		this.mmcUrl = mmcUrl;
		this.user = user;
		this.password = password;
		this.fileLocation = fileLocation;
		this.artifactName = artifactName;
		this.deploymentName = deploymentName == null || deploymentName.trim().isEmpty() ? artifactName : deploymentName;
		this.artifactVersion = artifactVersion;
		this.clusterOrServerGroupName = clusterOrServerGroupName;
		this.completeDeployment = completeDeployment;
		this.deployWithPomDetails = true;
		this.startupTimeout = startupTimeout;
		this.deleteOldDeployments = deleteOldDeployments;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener)
	{
		boolean success = false;

		listener.getLogger().println(">>> MMC URL IS " + mmcUrl);
		listener.getLogger().println(">>> USER IS " + user);
		listener.getLogger().println(">>> File URL IS " + fileLocation);
		listener.getLogger().println(">>> ArtifactName IS " + artifactName);
        listener.getLogger().println(">>> Artifact Version IS  " + artifactVersion);
		listener.getLogger().println(">>> DeploymentName IS " + deploymentName);
		listener.getLogger().println(">>> clusterOrServerGroupName IS " + clusterOrServerGroupName);
		listener.getLogger().println(">>> Preparing deployment....");

		try
		{
            final EnvVars envVars = build.getEnvironment(listener);
            final URL url = new URL(mmcUrl);

            HttpClientFactory httpFactory = new HttpClientFactory(mmcUrl, user, password);
            final CloseableHttpClient httpClient = httpFactory.createHttpClient();
            final HttpCoreContext httpContext = httpFactory.createHttpContext();
            MuleRest muleRest = new MuleRest(url, httpClient, httpContext);

			if (artifactVersion != null && artifactVersion.length()>0 && artifactName != null && artifactName.length()>0 )
			{
				success = deployFreestyleProject(build, listener, envVars, muleRest);
			} else {
				listener.getLogger().println("Plugin configuration for Artifact id/version required for Freestyle project. ");
			}

		} catch (IOException e)
		{
			listener.getLogger().println(e.toString());

		} catch (InterruptedException e)
		{
			listener.getLogger().println(e.toString());

		} catch (Exception e)
		{
			listener.getLogger().println(e.toString());
		}
		return success;
	}

    private boolean deployFreestyleProject(AbstractBuild<?, ?> build, BuildListener listener, EnvVars envVars, MuleRest muleRest) throws Exception {
        listener.getLogger().println("doing freestyle project deploy - using plugin configuration");
        boolean success = true;
        final FilePath[] deploymentArtifacts = build.getWorkspace().list(this.fileLocation);
        if (deploymentArtifacts.length == 0) {
            throw new Exception("No artifacts found for deployment");
        }
        for (FilePath file : deploymentArtifacts)
        {
            listener.getLogger().println(">>>>>>>>>>>> ARTIFACT ID: " + hudson.Util.replaceMacro(artifactName, envVars));
            listener.getLogger().println(">>>>>>>>>>>> VERSION: " + hudson.Util.replaceMacro(artifactVersion, envVars));
            listener.getLogger().println(">>>>>>>>>>>> FILE: "+ file.getRemote());
            listener.getLogger().println(">>>>>>>>>>>> TARGET: " + hudson.Util.replaceMacro(clusterOrServerGroupName, envVars));
            listener.getLogger().println(">>>>>>>>>>>> DEPLOYMENT: " + hudson.Util.replaceMacro(deploymentName, envVars));
            try {
                doDeploy(listener,
                        muleRest,
                        new File(file.getRemote()),
                        hudson.Util.replaceMacro(clusterOrServerGroupName, envVars),
                        hudson.Util.replaceMacro(artifactVersion, envVars),
                        hudson.Util.replaceMacro(artifactName, envVars),
                        hudson.Util.replaceMacro(deploymentName, envVars));
            } catch (StartupFailedException e) {
                success = false;
                listener.getLogger().println("Startup failed, undeploying...");
                doUndeploy(listener,
                        muleRest,
                        hudson.Util.replaceMacro(deploymentName, envVars));
            }
        }
        return success;
    }

    protected void doDeploy(BuildListener listener, MuleRest muleRest, File aFile, String target, String theVersion, String theApplicationName, String theDeploymentName) throws Exception
	{
		listener.getLogger().println("Deployment (" + theApplicationName + " " + theVersion + " to " + target + ")...");

		// delete application first
		if (isSnapshotVersion(theVersion))
		{
			listener.getLogger().println("Is Snapshot. Delete " + theApplicationName + " " + theVersion);
			muleRest.deleteApplication(theApplicationName, theVersion);
		}

		String versionId = muleRest.restfullyUploadRepository(theApplicationName, theVersion, aFile);

		// delete existing deployment with same name before creating new one
		muleRest.deleteDeployment(theDeploymentName);
		// undeploy other versions of that application on that target
		muleRest.undeploy(theApplicationName, target);

		if (deleteOldDeployments) {
			deleteDeployments(listener, muleRest, theApplicationName, target);
		}

		listener.getLogger().println("... create deployment");
		String deploymentId = muleRest.restfullyCreateDeployment(theDeploymentName, target, versionId);
		listener.getLogger().println("... create deployment finished");

		if (completeDeployment) {
			listener.getLogger().println("... start deployment");
			final long startTime = System.nanoTime();
			final long timeout = Long.valueOf(this.startupTimeout);
			muleRest.restfullyDeployDeploymentById(deploymentId);
            final String status = waitForStatus(listener, muleRest, deploymentId, startTime, timeout);
            if (STATUS_FAILED.equals(status))
                throw new StartupFailedException();
		}
		listener.getLogger().println("Deployment finished");
	}

    protected String waitForStatus(BuildListener listener, MuleRest muleRest, String deploymentId, long startTime, long timeout) throws Exception {
        String status = muleRest.restfullyGetDeploymentStatus(deploymentId);;
        listener.getLogger().println("....retreiving status: " + status);
        while (!isFinalStatus(status)) {
            Thread.sleep(50);
            if (timeout > 0 && startTime > 0 && (System.nanoTime() - startTime) > (timeout * 1000))
                throw new TimeoutException("Timeout during startup");
            status = muleRest.restfullyGetDeploymentStatus(deploymentId);
            listener.getLogger().println("....retreiving status: " + status);
        }
        return status;
	}

    protected static boolean isFinalStatus(String status) {
	    return !(status.equals(STATUS_IN_PROGRESS) || status.equals(STATUS_DELETING));
    }

    private boolean isSnapshotVersion(String version)
	{
		return version.contains(SNAPSHOT);
	}

	protected void deleteDeployments(BuildListener listener, MuleRest muleRest, String applicationName, String targetName) throws Exception {
        listener.getLogger().println("... delete deployment " + applicationName + " on " + targetName);
		Set<String> deploymentIds = muleRest.getAllDeployments(applicationName, targetName);
		for (String deploymentId : deploymentIds) {
            listener.getLogger().println("... ... delete deployment " + deploymentId);
            muleRest.restfullyDeleteDeploymentById(deploymentId);
            final String status = waitForStatus(listener, muleRest, deploymentId, 0, 0);
            if (!STATUS_DELETED.equals(status)) {
                throw new DeletingFailedException(deploymentId);
            }
		}
        listener.getLogger().println("... deleteDeployment " + applicationName + " on " + targetName + " finished");
	}

	protected void doUndeploy(BuildListener listener, MuleRest muleRest, String theDeploymentName) throws Exception
	{
		listener.getLogger().println("Delete deployment (" + theDeploymentName + ")...");
		muleRest.deleteDeployment(theDeploymentName);
		listener.getLogger().println("Deployment deleted");
	}

	// Overridden for better type safety.
	// If your plugin doesn't really define any property on Descriptor,
	// you don't have to do this.
	@Override
	public DescriptorImpl getDescriptor()
	{
		return (DescriptorImpl) super.getDescriptor();
	}

	/**
	 * Descriptor for {@link MMCDeployerBuilder}.
	 */
	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder>
	{

		/**
		 * In order to load the persisted global configuration, you have to call load() in the constructor.
		 */
		public DescriptorImpl() {
			load();
		}

		public FormValidation doTestConnection(@QueryParameter("mmcUrl") final String mmcUrl, @QueryParameter("user") final String user,
		        @QueryParameter("password") final String password) throws IOException, ServletException
		{
			try
			{
                HttpClientFactory httpFactory = new HttpClientFactory(mmcUrl, user, password);
                final CloseableHttpClient httpClient = httpFactory.createHttpClient();
                final HttpCoreContext httpContext = httpFactory.createHttpContext();
                URL url = new URL(mmcUrl);
                HttpGet get = new HttpGet(url.getPath() + "/deployments");
                HttpHost mmcHost = new HttpHost(url.getHost(), url.getPort(), url.getProtocol());
                final CloseableHttpResponse response = httpClient.execute(mmcHost, get, httpContext);
                if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
					return FormValidation.error("Client error: " + response.getStatusLine().getReasonPhrase());
                else if (!response.getEntity().getContentType().getValue().equalsIgnoreCase("application/json"))
					return FormValidation.error("access denied");
				else
					return FormValidation.ok("Success");
			} catch (Exception e)
			{
				return FormValidation.error("Client error : " + e.getMessage());
			}
		}

		public boolean isApplicable(Class<? extends AbstractProject> aClass)
		{
			// Indicates that this builder can be used with all kinds of project types
			return true;
		}

		/**
		 * This human readable name is used in the configuration screen.
		 */
		public String getDisplayName()
		{
			return "Deploy to Mule Management Console";
		}

	}

	public String getMmcUrl()
	{
		return mmcUrl;
	}

	public String getUser()
	{
		return user;
	}

	public String getPassword()
	{
		return password;
	}

	public String getFileLocation()
	{
		return fileLocation;
	}

	public String getArtifactName()
	{
		return artifactName;
	}

	public String getArtifactVersion()
	{
		return artifactVersion;
	}

	public String getStartupTimeout() { return startupTimeout; }

	public String clusterOrServerGroupName()
	{
		return clusterOrServerGroupName;
	}
}
