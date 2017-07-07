package org.jenkinsci.plugins.mulemmc;

import hudson.EnvVars;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashSet;

import static org.mockito.Mockito.when;

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

    private static AbstractBuild mockBuild;

    @BeforeClass
    public static void setup() throws Exception {
        buildListener = Mockito.mock(BuildListener.class);

        PrintStream logger = Mockito.mock(PrintStream.class);
        when(buildListener.getLogger()).thenReturn(logger);

        mockBuild = Mockito.mock(AbstractBuild.class);
        when(mockBuild.getEnvironment(buildListener)).thenReturn(new EnvVars());
    }

    @Test
    public void testDoDeploySuccessfullWithoutTimeout() throws Exception {

        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyUploadRepository(APP_NAME, VERSION, null)).thenReturn(VERSIONID);
        when(mockRest.restfullyCreateDeployment(DEPLOYMENT_NAME, TARGET_NAME, VERSIONID)).thenReturn(DEPLOYMENTID);
        when(mockRest.restfullyGetDeploymentStatus(DEPLOYMENTID)).thenReturn("IN PROGRESS", "IN PROGRESS", "IN PROGRESS", "DEPLOYED");
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                "test.zip", APP_NAME, DEPLOYMENT_NAME, VERSION, false, "-1");
        deployer.doDeploy(buildListener, mockRest, null, TARGET_NAME, VERSION, APP_NAME, DEPLOYMENT_NAME);
    }

    @Test(expected=Exception.class)
    public void testDoDeployWithTimeout() throws Exception {
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyUploadRepository(APP_NAME, VERSION, null)).thenReturn(VERSIONID);
        when(mockRest.restfullyCreateDeployment(DEPLOYMENT_NAME, TARGET_NAME, VERSIONID)).thenReturn(DEPLOYMENTID);
        when(mockRest.restfullyGetDeploymentStatus(DEPLOYMENTID)).thenReturn("IN PROGRESS", "IN PROGRESS", "IN PROGRESS", "DEPLOYED");
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                "test.zip", APP_NAME, DEPLOYMENT_NAME, VERSION, false,  "1");
        deployer.doDeploy(buildListener, mockRest, null, TARGET_NAME, VERSION, APP_NAME, DEPLOYMENT_NAME);
    }

    @Test(expected=Exception.class)
    public void testDoDeployFailed() throws Exception {
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyUploadRepository(APP_NAME, VERSION, null)).thenReturn(VERSIONID);
        when(mockRest.restfullyCreateDeployment(DEPLOYMENT_NAME, TARGET_NAME, VERSIONID)).thenReturn(DEPLOYMENTID);
        when(mockRest.restfullyGetDeploymentStatus(DEPLOYMENTID)).thenReturn("IN PROGRESS", "IN PROGRESS", "IN PROGRESS", "FAILED");
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(null, null, null, true, TARGET_NAME,
                "test.zip", APP_NAME, DEPLOYMENT_NAME, VERSION, false, "-1");
        deployer.doDeploy(buildListener, mockRest, null, TARGET_NAME, VERSION, APP_NAME, DEPLOYMENT_NAME);
    }

    @Test(expected=Exception.class)
    public void testDoDeployWithInvalidTimeout() throws Exception {
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyUploadRepository(APP_NAME, VERSION, null)).thenReturn(VERSIONID);
        when(mockRest.restfullyCreateDeployment(DEPLOYMENT_NAME, TARGET_NAME, VERSIONID)).thenReturn(DEPLOYMENTID);
        when(mockRest.restfullyGetDeploymentStatus(DEPLOYMENTID)).thenReturn("IN PROGRESS", "IN PROGRESS", "IN PROGRESS", "FAILED");
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(null, null, null, true, TARGET_NAME,
                "test.zip", APP_NAME, DEPLOYMENT_NAME, VERSION, false, "");
        deployer.doDeploy(buildListener, mockRest, null, TARGET_NAME, VERSION, APP_NAME, DEPLOYMENT_NAME);
    }

    @Test
    public void testDeployFreestyleProjectWithoutArtifact() throws Exception {

        MuleRest mockRest = Mockito.mock(MuleRest.class);
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                file, APP_NAME, DEPLOYMENT_NAME, VERSION, false, "");
        boolean result = deployer.perform(mockBuild, null, buildListener);
        assert(!result);
    }

    @Test
    public void testDeployFreestyleProjectWithoutAppname() throws Exception {

        MuleRest mockRest = Mockito.mock(MuleRest.class);
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                "test.zip", null, DEPLOYMENT_NAME, VERSION, false, "");
        boolean result = deployer.perform(mockBuild, null, buildListener);
        assert(!result);
    }

    @Test
    public void testDeployFreestyleProjectWithoutVersion() throws Exception {

        MuleRest mockRest = Mockito.mock(MuleRest.class);
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                "test.zip", APP_NAME, DEPLOYMENT_NAME, null, false, "");
        boolean result = deployer.perform(mockBuild, null, buildListener);
        assert(!result);
    }

    @Test
    public void testWaitForStatusOnce() throws Exception {

        final String id = "deployment-id";
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyGetDeploymentStatus(id)).thenReturn(MMCDeployerBuilder.STATUS_DEPLOYED);
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                file, APP_NAME, DEPLOYMENT_NAME, null, false, "");
        final String status = deployer.waitForStatus(buildListener, mockRest, id, 0, 0);
        assert(MMCDeployerBuilder.STATUS_DEPLOYED.equals(status));
    }

    @Test
    public void testWaitForStatusInProgress() throws Exception {

        final String id = "deployment-id";
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyGetDeploymentStatus(id)).thenReturn(MMCDeployerBuilder.STATUS_IN_PROGRESS, MMCDeployerBuilder.STATUS_DEPLOYED);
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                file, APP_NAME, DEPLOYMENT_NAME, null, false, "");
        final String status = deployer.waitForStatus(buildListener, mockRest, id, 0, 0);
        assert(MMCDeployerBuilder.STATUS_DEPLOYED.equals(status));
    }

    @Test
    public void testWaitForStatusDeleting() throws Exception {

        final String id = "deployment-id";
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyGetDeploymentStatus(id)).thenReturn(MMCDeployerBuilder.STATUS_DELETING, MMCDeployerBuilder.STATUS_DELETED);
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                file, APP_NAME, DEPLOYMENT_NAME, null, false, "");
        final String status = deployer.waitForStatus(buildListener, mockRest, id, 0, 0);
        assert(MMCDeployerBuilder.STATUS_DELETED.equals(status));
    }

    @Test
    public void testWaitForStatusFailed() throws Exception {

        final String id = "deployment-id";
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.restfullyGetDeploymentStatus(id)).thenReturn(MMCDeployerBuilder.STATUS_FAILED);
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                file, APP_NAME, DEPLOYMENT_NAME, null, false, "");
        final String status = deployer.waitForStatus(buildListener, mockRest, id, 0, 0);
        assert(MMCDeployerBuilder.STATUS_FAILED.equals(status));
    }

    @Test
    public void testIsFinalStatusForInProgress() {
        assert(!MMCDeployerBuilder.isFinalStatus(MMCDeployerBuilder.STATUS_IN_PROGRESS));
    }

    @Test
    public void testIsFinalStatusForDeleting() {
        assert(!MMCDeployerBuilder.isFinalStatus(MMCDeployerBuilder.STATUS_DELETING));
    }

    @Test
    public void testIsFinalStatusForDeployed() {
        assert(MMCDeployerBuilder.isFinalStatus(MMCDeployerBuilder.STATUS_DEPLOYED));
    }

    @Test
    public void testIsFinalStatusForFailed() {
        MMCDeployerBuilder.isFinalStatus(MMCDeployerBuilder.STATUS_DEPLOYED);
    }

    @Test
    public void testDeleteDeployments() throws Exception {
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.getAllDeployments("app", "target")).thenReturn(new HashSet<String>(Arrays.asList("id1")));
        when(mockRest.restfullyGetDeploymentStatus("id1")).thenReturn(MMCDeployerBuilder.STATUS_DELETED);
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                file, APP_NAME, DEPLOYMENT_NAME, null, false, "");
        deployer.deleteDeployments(buildListener, mockRest, "app", "target");
        org.mockito.Mockito.verify(mockRest).restfullyDeleteDeploymentById("id1");
    }

    @Test
    public void testDeleteDeploymentsTwoDeployments() throws Exception {
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.getAllDeployments("app", "target")).thenReturn(new HashSet<String>(Arrays.asList("id1", "id2")));
        when(mockRest.restfullyGetDeploymentStatus("id1")).thenReturn(MMCDeployerBuilder.STATUS_DELETED);
        when(mockRest.restfullyGetDeploymentStatus("id2")).thenReturn(MMCDeployerBuilder.STATUS_DELETED);
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                file, APP_NAME, DEPLOYMENT_NAME, null, false, "");
        deployer.deleteDeployments(buildListener, mockRest, "app", "target");
        org.mockito.Mockito.verify(mockRest).restfullyDeleteDeploymentById("id1");
        org.mockito.Mockito.verify(mockRest).restfullyDeleteDeploymentById("id2");
    }

    @Test(expected = MMCDeployerBuilder.DeletingFailedException.class)
    public void testDeleteDeploymentsFailure() throws Exception {
        MuleRest mockRest = Mockito.mock(MuleRest.class);
        when(mockRest.getAllDeployments("app", "target")).thenReturn(new HashSet<String>(Arrays.asList("id1")));
        when(mockRest.restfullyGetDeploymentStatus("id1")).thenReturn(MMCDeployerBuilder.STATUS_FAILED);
        final String file = null;
        MMCDeployerBuilder deployer = new MMCDeployerBuilder(MMC_URL, null, null, true, TARGET_NAME,
                file, APP_NAME, DEPLOYMENT_NAME, null, false, "");
        deployer.deleteDeployments(buildListener, mockRest, "app", "target");
        org.mockito.Mockito.verify(mockRest).restfullyDeleteDeploymentById("id1");
    }

}
