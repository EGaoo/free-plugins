package org.freejava.tools.handlers;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

public class ArtifactorySourceCodeFinder extends AbstractSourceCodeFinder implements SourceCodeFinder {

	private boolean canceled = false;
	private String serviceUrl;

	public ArtifactorySourceCodeFinder(String serviceUrl) {
		this.serviceUrl = serviceUrl;
	}

    public void cancel() {
		this.canceled = true;
    }

    public void find(String binFile, List<SourceFileResult> results) {
        Collection<GAV> gavs = new HashSet<GAV>();
		try {
			String sha1;
	        FileInputStream fis = FileUtils.openInputStream(new File(binFile));
	        try {
	        	sha1 = DigestUtils.shaHex(fis);
	        } finally {
	        	IOUtils.closeQuietly(fis);
	        }
	        gavs.addAll(findArtifactsUsingArtifactory(null, null, null, null, sha1, false));
        } catch (Exception e) {
            e.printStackTrace();
        }

		if (canceled) return;

		try {
			gavs.addAll(findGAVFromFile(binFile));
        } catch (Exception e) {
            e.printStackTrace();
        }

		if (canceled) return;

		Map<GAV, String> sourcesUrls = new HashMap<GAV, String>();
		try {
			sourcesUrls.putAll(findSourcesUsingArtifactory(gavs));
        } catch (Exception e) {
            e.printStackTrace();
        }

		for (Map.Entry<GAV, String> entry : sourcesUrls.entrySet()) {
			String name = entry.getKey().getA() + '-' + entry.getKey().getV() + "-sources.jar";
        	try {
        		String result = download(entry.getValue());
	        	if (result != null && isSourceCodeFor(result, binFile)) {
	        		results.add(new SourceFileResult(binFile, result, name, 100));
	        	}
        	} catch (Exception e) {
				e.printStackTrace();
			}
		}
    }

	private Map<GAV, String> findSourcesUsingArtifactory(Collection<GAV> gavs) throws Exception {
		Map<GAV, String> results = new HashMap<GAV, String>();
        for (GAV gav : gavs) {
        	if (canceled) return results;
        	Set<GAV> gavs2 = findArtifactsUsingArtifactory(gav.getG(), gav.getA(), gav.getV(), "sources", null, true);
	        for (GAV gav2 : gavs2) {
	        	results.put(gav, gav2.getArtifactLink());
	        }
        }

        return results;
	}

	private Set<GAV> findArtifactsUsingArtifactory(String g, String a, String v, String c, String sha1, boolean getLink) throws Exception {
		//https://repository.cloudera.com/artifactory/api/search/checksum?sha1=2bf96b7aa8b611c177d329452af1dc933e14501c

		//{"results":[{"uri":"http://repository.cloudera.com/artifactory/api/storage/repo1-cache/commons-cli/commons-cli/1.2/commons-cli-1.2.jar"}]}

        //GET /api/search/gavc?g=org.acme&a=artifact*&v=1.0&c=sources&repos=libs-release-local

		Set<GAV> results = new HashSet<GAV>();
		String apiUrl = getArtifactApiUrl();

		String url;
		if (sha1 != null) {
			url = apiUrl + "/search/checksum?sha1=" + sha1;
		} else {
			url = apiUrl + "/search/gavc?g=" + g + "&a=" + a + "&v=" + v + (c != null ? "&c=" + c : "");
		}

        URLConnection connection = new URL(url).openConnection();
        connection.connect();
        try {
        	InputStream is = connection.getInputStream();
        	String json = IOUtils.toString(is);
        	IOUtils.closeQuietly(is);

        	JSONObject resp = (JSONObject) JSONSerializer.toJSON( json );
        	for (Object elem : ((JSONArray) resp.get("results")).toArray()) {
        		JSONObject result = (JSONObject) elem;
        		String uri = (String) result.get("uri");
        		//http://repository.cloudera.com/artifactory/api/storage/repo1-cache/commons-cli/commons-cli/1.2/commons-cli-1.2.jar
        		String regex = "/api/storage/[^/]+/(.+)$";
        	    Pattern pattern = Pattern.compile(regex);
        	    Matcher matcher = pattern.matcher(uri);
        	    if (matcher.find()) {
        	    	String[] gavInArray = matcher.group(1).split("/");

        	    	GAV gav = new GAV();
            		String group = gavInArray[0];
            		for (int i = 1; i < gavInArray.length-3; i++) {
            			group += gavInArray[i];
            		}
            		gav.setG(group);

            		gav.setA(gavInArray[gavInArray.length-3]);
            		gav.setV(gavInArray[gavInArray.length-2]);

            		if (getLink) gav.setArtifactLink(uri);
            		results.add(gav);
        	    }

        	}

        } catch (Exception e) {
        	e.printStackTrace();
		}

        return results;
	}

	private String getArtifactApiUrl() {
		String result = null;
		if (serviceUrl.endsWith("/webapp/home.html")) {
			result = serviceUrl.replace("/webapp/home.html", "/api/");
		}
		return result;
	}
}
