package org.jenkinsci.plugins.mulemmc;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import hudson.maven.reporters.MavenArtifact;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.auth.AuthPolicy;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.httpclient.methods.multipart.FilePart;
import org.apache.commons.httpclient.methods.multipart.MultipartRequestEntity;
import org.apache.commons.httpclient.methods.multipart.Part;
import org.apache.commons.httpclient.methods.multipart.StringPart;
import org.apache.commons.httpclient.UsernamePasswordCredentials;

import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.junit.Test;

public class MmcTest {

	private static Logger logger = LogManager.getLogger("MmcTest");
	
	
	public static void main(String args[]) throws Exception {
		PropertyConfigurator.configure("src/test/resources/log4j.properties");
		
		File testFile = new File("/Users/eberman/Development/MuleSoft/mule/mule-enterprise-standalone-3.4.2/examples/echo/mule-example-echo-3.4.2.zip");
		
        HttpClient httpclient = new HttpClient();
        
        httpclient.getState().setCredentials(
        		new AuthScope("localhost", 8080),
                new UsernamePasswordCredentials("admin", "admin"));

        List authPrefs = new ArrayList(3);
        authPrefs.add(AuthPolicy.BASIC);
        httpclient.getParams().setParameter(AuthPolicy.AUTH_SCHEME_PRIORITY, authPrefs);
        httpclient.getParams().setAuthenticationPreemptive(true);
        
        PostMethod post = new PostMethod("http://localhost:8080/mmc/api/repository");
        post.setDoAuthentication(true);
        
        Part[] parts = {
        	new FilePart("file", testFile),
        	new StringPart("name", "mule-example-echo"),
        	new StringPart("version", new SimpleDateFormat("yyyyMMdd'T'HHmmssz").format(new Date()))
        };
        
        MultipartRequestEntity multipartEntity = new MultipartRequestEntity(parts, post.getParams());        
        post.setRequestEntity(multipartEntity);        

        httpclient.executeMethod(post);

//        HttpResponse response = httpclient.execute(post, localContext);
        logger.debug(">>>>> RESPONSE IS " + post.getResponseBodyAsString());
//        
//        HttpEntity entity = response.getEntity();
//
//        InputStream stream = entity.getContent();
//        
//        byte[] bytes=new byte[stream.available()];
//        stream.read(bytes);
//        String s = new String(bytes);
//        
//        
//        // response.getStatusLine();  // CONSIDER  Detect server complaints
//
//        entity.consumeContent();
//        httpclient.getConnectionManager().shutdown(); 

	}
}
