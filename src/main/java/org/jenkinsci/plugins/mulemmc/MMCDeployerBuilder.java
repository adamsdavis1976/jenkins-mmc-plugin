package org.jenkinsci.plugins.mulemmc;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.maven.MavenBuild;
import hudson.maven.MavenModuleSetBuild;
import hudson.maven.reporters.MavenArtifact;
import hudson.maven.reporters.MavenArtifactRecord;
import hudson.model.BuildListener;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.FormValidation;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletException;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthPolicy;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.GetMethod;
import org.jsoup.helper.StringUtil;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

public class MMCDeployerBuilder extends Builder
{

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
	        String fileLocation, String artifactName, String deploymentName, String artifactVersion, boolean deleteOldDeployments, String startupTimeout) {
		this.mmcUrl = mmcUrl;
		this.user = user;
		this.password = password;
		this.fileLocation = fileLocation;
		this.artifactName = artifactName;
		this.deploymentName = StringUtil.isBlank(deploymentName) ? artifactName : deploymentName;
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
		EnvVars envVars = new EnvVars();

		listener.getLogger().println(">>> MMC URL IS " + mmcUrl);
		listener.getLogger().println(">>> USER IS " + user);
		listener.getLogger().println(">>> File URL IS " + fileLocation);
		listener.getLogger().println(">>> ArtifactName IS " + artifactName);
        listener.getLogger().println(">>> Artifact Version IS  " + artifactVersion);
		listener.getLogger().println(">>> DeploymentName IS " + deploymentName);
		listener.getLogger().println(">>> clusterOrServerGroupName IS " + clusterOrServerGroupName);
		listener.getLogger().println(">>> Beginning deployment....");

		try
		{
			envVars = build.getEnvironment(listener);

			// aFile = getFile(workspace, fileLocation);
			MuleRest muleRest = new MuleRest(new URL(mmcUrl), user, password);

			if (build instanceof MavenModuleSetBuild)
			{
                success = deployMavenBuild((MavenModuleSetBuild) build, listener, envVars, muleRest);
			} else {
                if (artifactVersion != null && artifactVersion.length()>0 && artifactName != null && artifactName.length()>0 )
                {
                    success = deployFreestyleProject(build, listener, envVars, muleRest);
                } else {
                    listener.getLogger().println("Plugin configuration for Artifact id/version required for Freestyle project. ");
                }
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
            listener.getLogger().println(">>>>>>>>>>>> VERSION: " +hudson.Util.replaceMacro(artifactVersion, envVars));
            listener.getLogger().println(">>>>>>>>>>>> FILE: "+ file.getRemote());
            listener.getLogger().println(">>>>>>>>>>>> SERVER: " +hudson.Util.replaceMacro(clusterOrServerGroupName, envVars));
            listener.getLogger().println(">>>>>>>>>>>> DEPLOYMENT: " +hudson.Util.replaceMacro(deploymentName, envVars));
            try {
                doDeploy(listener,
                        muleRest,
                        new File(file.getRemote()),
                        hudson.Util.replaceMacro(clusterOrServerGroupName, envVars),
                        hudson.Util.replaceMacro(artifactVersion, envVars),
                        hudson.Util.replaceMacro(artifactName, envVars),
                        hudson.Util.replaceMacro(deploymentName, envVars));
            } catch (Exception e) {
                success = false;
                listener.getLogger().println("Deployment failed, undeploying...");
                doUndeploy(listener,
                        muleRest,
                        hudson.Util.replaceMacro(clusterOrServerGroupName, envVars),
                        hudson.Util.replaceMacro(artifactVersion, envVars),
                        hudson.Util.replaceMacro(artifactName, envVars),
                        hudson.Util.replaceMacro(deploymentName, envVars));
            }
        }
        return success;
    }

    private boolean deployMavenBuild(MavenModuleSetBuild build, BuildListener listener, EnvVars envVars, MuleRest muleRest) throws Exception {
        listener.getLogger().println("doing maven deploy based on maven artifact details in POM");
        boolean success = true;
        for (final List<MavenBuild> mavenBuilds : build.getModuleBuilds().values())
        {
            for (final MavenBuild mavenBuild : mavenBuilds)
            {

                MavenArtifactRecord record = mavenBuild.getMavenArtifacts();

                MavenArtifact mainArtifact = record.mainArtifact;

                if (record.isPOM())
                {
                    List<MavenArtifact> attachedArtifacts = record.attachedArtifacts;
                    for (final MavenArtifact nextAttached : attachedArtifacts)
                    {
                        listener.getLogger().println(">>>>>>>>>>>> ARTIFACT ID: " + nextAttached.artifactId);
                        listener.getLogger().println(">>>>>>>>>>>> VERSION: " + nextAttached.version);
                        listener.getLogger().println(">>>>>>>>>>>> FILE: " + nextAttached.getFile(mavenBuild).getAbsolutePath());
                        try {
							doDeploy(listener,
									muleRest,
									nextAttached.getFile(mavenBuild),
									hudson.Util.replaceMacro(clusterOrServerGroupName, envVars),
									nextAttached.version,
									nextAttached.artifactId,
									deploymentName);
						} catch (Exception e) {
                        	success = false;
							listener.getLogger().println("Deployment failed, undeploying...");
							doUndeploy(listener,
									muleRest,
									hudson.Util.replaceMacro(clusterOrServerGroupName, envVars),
									nextAttached.version,
									nextAttached.artifactId,
									deploymentName);
						}
                    }
                }
            }
        }
        return success;
    }

    protected void doDeploy(BuildListener listener, MuleRest muleRest, File aFile, String clusterOrServerGroupName, String theVersion, String theApplicationName, String theDeploymentName) throws Exception
	{
		listener.getLogger().println("Deployment starting (" + theApplicationName + " " + theVersion + " to " + clusterOrServerGroupName + ")...");
		String versionId = muleRest.restfullyUploadRepository(theApplicationName, theVersion, aFile);
        // delete existing deployment before creating new one
        muleRest.restfullyDeleteDeployment(theDeploymentName);
        // undeploy other versions of that application on that target
        muleRest.undeploy(theApplicationName, clusterOrServerGroupName);

        if (deleteOldDeployments) {
            muleRest.deleteDeployments(theApplicationName, clusterOrServerGroupName);
        }

        String deploymentId = muleRest.restfullyCreateDeployment(theDeploymentName, clusterOrServerGroupName, theApplicationName, versionId);
		if (completeDeployment){
			final long startTime = System.nanoTime();
			final long timeout = Long.valueOf(this.startupTimeout);
			muleRest.restfullyDeployDeploymentById(deploymentId);
			String status;
			do {
                if (timeout > 0 && (System.nanoTime() - startTime) > timeout * 1000)
                    throw new Exception("Timeout during startup");
				status = muleRest.restfullyWaitStartupForCompletion(deploymentId);
				listener.getLogger().println("....retreiving status: " + status);
				Thread.sleep(50);
				if (status.equals("FAILED"))
					throw new Exception("Startup failed.");
			} while (status.equals("IN PROGRESS"));
		}
		listener.getLogger().println("Deployment finished");
	}

	protected void doUndeploy(BuildListener listener, MuleRest muleRest, String clusterOrServerGroupName, String theVersion, String theApplicationName, String theDeploymentName) throws Exception
	{
		listener.getLogger().println("Undeployment starting (" + theApplicationName + " " + theVersion + " to " + clusterOrServerGroupName + ")...");
		// delete existing deployment before creating new one
		muleRest.restfullyDeleteDeployment(theDeploymentName);
		listener.getLogger().println("Undeployment finished");
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
				URL url = new URL(mmcUrl);
				HttpClient client = new HttpClient();

				client.getState().setCredentials(new AuthScope(url.getHost(), url.getPort()),
				        new UsernamePasswordCredentials(user, password));

				List authPrefs = new ArrayList(3);
				authPrefs.add(AuthPolicy.BASIC);
				client.getParams().setParameter(AuthPolicy.AUTH_SCHEME_PRIORITY, authPrefs);
				client.getParams().setAuthenticationPreemptive(true);

				GetMethod method = new GetMethod(mmcUrl + "/deployments");
				int statusCode = client.executeMethod(method);

				if (statusCode == 200) return FormValidation.ok("Success");
				else return FormValidation.error("Client error : " + method.getStatusText());
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
