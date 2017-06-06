package org.jenkinsci.plugins.mulemmc;

import static org.mockito.Mockito.*;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Environment;
import hudson.model.EnvironmentList;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import org.hamcrest.*;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by christianlangmann on 22/03/2017.
 */
public class MMCDeployerBuilderTest {

    public static final String DEPLOYMENT_NAME = "deployment";
    public static final String TARGET_NAME = "Test";
    public static final String APP_NAME = "testapp";
    public static final String VERSION = "1.0.0";
    public static final String FILENAME = "testapp.zip";
    public static final String VERSIONID = "versionid";
    public static final String DEPLOYMENTID = "deploymentid";
    public static final String MMC_URL = "http://localhost/mmc/api";

    private static BuildListener buildListener;

    @BeforeClass
    public static void setup() {
        buildListener = Mockito.mock(BuildListener.class);
        PrintStream logger = Mockito.mock(PrintStream.class);
        when(buildListener.getLogger()).thenReturn(logger);
    }

    @Test
    public void testDoDeploySuccessfullWithoutTimeout() throws Exception {

        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyUploadRepository(APP_NAME, VERSION, null)).thenReturn(VERSIONID);
        when(mockRest.restfullyCreateDeployment(DEPLOYMENT_NAME, TARGET_NAME, VERSIONID)).thenReturn(DEPLOYMENTID);
        when(mockRest.restfullyWaitStartupForCompletion(DEPLOYMENTID)).thenReturn("IN PROGRESS", "IN PROGRESS", "IN PROGRESS", "DEPLOYED");
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                "test.zip", APP_NAME, DEPLOYMENT_NAME, VERSION, false, "-1");
        deployer.doDeploy(buildListener, mockRest, null, TARGET_NAME, VERSION, APP_NAME, DEPLOYMENT_NAME);
    }

    @Test(expected=Exception.class)
    public void testDoDeployWithTimeout() throws Exception {
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyUploadRepository(APP_NAME, VERSION, null)).thenReturn(VERSIONID);
        when(mockRest.restfullyCreateDeployment(DEPLOYMENT_NAME, TARGET_NAME, VERSIONID)).thenReturn(DEPLOYMENTID);
        when(mockRest.restfullyWaitStartupForCompletion(DEPLOYMENTID)).thenReturn("IN PROGRESS", "IN PROGRESS", "IN PROGRESS", "DEPLOYED");
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                "test.zip", APP_NAME, DEPLOYMENT_NAME, VERSION, false,  "1");
        deployer.doDeploy(buildListener, mockRest, null, TARGET_NAME, VERSION, APP_NAME, DEPLOYMENT_NAME);
    }

    @Test(expected=Exception.class)
    public void testDoDeployFailed() throws Exception {
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyUploadRepository(APP_NAME, VERSION, null)).thenReturn(VERSIONID);
        when(mockRest.restfullyCreateDeployment(DEPLOYMENT_NAME, TARGET_NAME, VERSIONID)).thenReturn(DEPLOYMENTID);
        when(mockRest.restfullyWaitStartupForCompletion(DEPLOYMENTID)).thenReturn("IN PROGRESS", "IN PROGRESS", "IN PROGRESS", "FAILED");
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(null, null, null, true, TARGET_NAME,
                "test.zip", APP_NAME, DEPLOYMENT_NAME, VERSION, false, "-1");
        deployer.doDeploy(buildListener, mockRest, null, TARGET_NAME, VERSION, APP_NAME, DEPLOYMENT_NAME);
    }

    @Test(expected=Exception.class)
    public void testDoDeployWithInvalidTimeout() throws Exception {
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyUploadRepository(APP_NAME, VERSION, null)).thenReturn(VERSIONID);
        when(mockRest.restfullyCreateDeployment(DEPLOYMENT_NAME, TARGET_NAME, VERSIONID)).thenReturn(DEPLOYMENTID);
        when(mockRest.restfullyWaitStartupForCompletion(DEPLOYMENTID)).thenReturn("IN PROGRESS", "IN PROGRESS", "IN PROGRESS", "FAILED");
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(null, null, null, true, TARGET_NAME,
                "test.zip", APP_NAME, DEPLOYMENT_NAME, VERSION, false, "");
        deployer.doDeploy(buildListener, mockRest, null, TARGET_NAME, VERSION, APP_NAME, DEPLOYMENT_NAME);
    }

    @Test
    public void testDeployFreestyleProjectWithoutArtifact() throws Exception {

        MuleRest mockRest = Mockito.mock(MuleRest.class);
        AbstractBuild mockBuild = Mockito.mock(AbstractBuild.class);
        when(mockBuild.getEnvironment(buildListener)).thenReturn(new EnvVars());
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                file, APP_NAME, DEPLOYMENT_NAME, VERSION, false, "");
        boolean result = deployer.perform(mockBuild, null, buildListener);
        assert(!result);
    }

    @Test
    public void testDeployFreestyleProjectWithoutAppname() throws Exception {

        MuleRest mockRest = Mockito.mock(MuleRest.class);
        AbstractBuild mockBuild = Mockito.mock(AbstractBuild.class);
        when(mockBuild.getEnvironment(buildListener)).thenReturn(new EnvVars());
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                "test.zip", null, DEPLOYMENT_NAME, VERSION, false, "");
        boolean result = deployer.perform(mockBuild, null, buildListener);
        assert(!result);
    }

    @Test
    public void testDeployFreestyleProjectWithoutVersion() throws Exception {

        MuleRest mockRest = Mockito.mock(MuleRest.class);
        AbstractBuild mockBuild = Mockito.mock(AbstractBuild.class);
        when(mockBuild.getEnvironment(buildListener)).thenReturn(new EnvVars());
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                "test.zip", APP_NAME, DEPLOYMENT_NAME, null, false, "");
        boolean result = deployer.perform(mockBuild, null, buildListener);
        assert(!result);
    }

}