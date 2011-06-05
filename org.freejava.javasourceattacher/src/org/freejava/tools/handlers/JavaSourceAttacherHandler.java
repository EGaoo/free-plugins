package org.freejava.tools.handlers;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.ivy.Ivy;
import org.apache.ivy.core.report.ResolveReport;
import org.apache.ivy.core.resolve.ResolveOptions;
import org.apache.ivy.core.settings.IvySettings;
import org.apache.ivy.plugins.resolver.IBiblioResolver;
import org.cyberneko.html.parsers.DOMParser;
import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.html.HTMLCollection;
import org.w3c.dom.html.HTMLDocument;
import org.xml.sax.SAXException;

/**
 * Handler class for View Class/Package Dependency action.
 *
 * @see org.eclipse.core.commands.IHandler
 * @see org.eclipse.core.commands.AbstractHandler
 */
public class JavaSourceAttacherHandler extends AbstractHandler {
    /**
     * The constructor.
     */
    public JavaSourceAttacherHandler() {
    }

    /**
     * the command has been executed, so extract extract the needed information
     * from the application context.
     */
    public Object execute(ExecutionEvent event) throws ExecutionException {
        ISelection selection = HandlerUtil.getCurrentSelection(event);

        if (!(selection instanceof IStructuredSelection)) {
            return null;
        }
        // * <li><code>org.eclipse.jdt.core.IPackageFragmentRoot</code></li>
        // * <li><code>org.eclipse.jdt.core.IJavaProject</code></li>
        IStructuredSelection structuredSelection = (IStructuredSelection) selection;
        List<IPackageFragmentRoot> selections = new ArrayList<IPackageFragmentRoot>();
        for (Iterator<?> iterator = structuredSelection.iterator(); iterator.hasNext();) {
            IJavaElement aSelection = (IJavaElement) iterator.next();
            if (aSelection instanceof IPackageFragmentRoot) {
                IPackageFragmentRoot pkgRoot = (IPackageFragmentRoot) aSelection;
                selections.add(pkgRoot);
            } else if (aSelection instanceof IJavaProject) {
                IJavaProject p = (IJavaProject) aSelection;
                try {
                    for (IPackageFragmentRoot pkgRoot : p.getPackageFragmentRoots()) {
                        selections.add(pkgRoot);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        updateSourceAttachments(selections);

        return null;
    }

    private void updateSourceAttachments(List<IPackageFragmentRoot> roots) {
        for (IPackageFragmentRoot pkgRoot : roots) {
            try {
                if (pkgRoot.getKind() == IPackageFragmentRoot.K_BINARY && pkgRoot.getSourceAttachmentPath() == null && pkgRoot.isArchive()) {
                    File file;
                    if (!pkgRoot.isExternal()) {
                        file = pkgRoot.getResource().getLocation().toFile();
                    } else {
                        file = pkgRoot.getPath().toFile();
                    }

                    boolean foundSrc = false;
                    // Try to find source using Maven repos
                    GAV gav = getGAV(file);
                    if (gav != null) {
                        File sourceFile = resolveArtifactSrc(gav);
                        if (sourceFile != null) {
                            //pkgRoot.attachSource(new Path(sourceFile.getAbsolutePath()), new Path(""), null);
                            attachSource(pkgRoot, sourceFile.getAbsolutePath(), "");
                            foundSrc = true;
                        }
                    }

                    if (!foundSrc) {
                        File sourceFile = getSourceFileFromGoogle(file);
                        if (sourceFile != null) {
                            attachSource(pkgRoot, sourceFile.getAbsolutePath(), null);
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }


    }
    protected static void attachSource(IPackageFragmentRoot root, String sourcePath, String sourceRoot) throws JavaModelException {
        IJavaProject javaProject = root.getJavaProject();
        IClasspathEntry[] entries = (IClasspathEntry[])javaProject.getRawClasspath().clone();
        boolean found = false;
        for (int i = 0; i < entries.length; i++){
                IClasspathEntry entry = entries[i];
                String entryPath = entry.getPath().toOSString();
                String rootPath = root.getPath().toOSString();
                if (entryPath.equals(rootPath)) {
                        entries[i] = JavaCore.newLibraryEntry(
                                root.getPath(),
                                sourcePath == null ? null : new Path(sourcePath),
                                sourceRoot == null ? null : new Path(sourceRoot),
                                false);
                        found = true;
                        break;
                }
        }
        if (!found) {
            // try to use file name only
            for (int i = 0; i < entries.length; i++){
                IClasspathEntry entry = entries[i];
                String entryPath = entry.getPath().toOSString();
                String entryName = entryPath.substring(Math.max(entryPath.lastIndexOf('/'), entryPath.lastIndexOf('\\')) + 1);
                String rootPath = root.getPath().toOSString();
                String rootName = rootPath.substring(Math.max(rootPath.lastIndexOf('/'), rootPath.lastIndexOf('\\')) + 1);
                if (entryName.equals(rootName)) {
                    // Problem: lost classpath variable here!!!
                    entries[i] = JavaCore.newLibraryEntry(
                            root.getPath(),
                            sourcePath == null ? null : new Path(sourcePath),
                            sourceRoot == null ? null : new Path(sourceRoot),
                            false);
                    found = true;
                    break;
                }
            }
        }
        javaProject.setRawClasspath(entries, null);
    }

    private static GAV getGAV(File binFile) throws IOException {
        GAV result = null;
        String sha1 = DigestUtils.shaHex(FileUtils.openInputStream(binFile));
        System.out.println(sha1);
        result = getGAVOnCentralBySHA1(binFile, sha1);
        if (result == null) {
        //    result = getGAVOnSonatypeBySHA1(binFile, sha1);
        }
        return result;
    }

    private static GAV getGAVOnCentralBySHA1(File binFile, String sha1) {
        GAV gav = null;
        try {
            String json = IOUtils.toString(new URL("http://search.maven.org/solrsearch/select?q=" + URLEncoder.encode("1:\"" + sha1 + "\"", "UTF-8") + "&rows=20&wt=json").openStream());
            JSONObject jsonObject = JSONObject.fromObject(json);
            JSONObject response = jsonObject.getJSONObject("response");
            for (int i = 0; i < response.getInt("numFound"); i++) {
                JSONArray docs = response.getJSONArray("docs");
                JSONObject doci = docs.getJSONObject(i);
                boolean hasSource = false;
                JSONArray ec = doci.getJSONArray("ec");
                String[] s = new String[ec.size()];
                for (int j = 0; j < ec.size(); j++) {
                    if ("sources.jar".equals(ec.getString(j))) {
                        hasSource  = true;
                        break;
                    }
                }
                if (!hasSource) continue;

                gav = new GAV();
                gav.setRepositoryUrl("http://repo1.maven.org/maven2/");
                gav.setG(doci.getString("g"));
                gav.setA(doci.getString("a"));
                gav.setV(doci.getString("v"));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return gav;
    }

    private static GAV getGAVOnSonatypeBySHA1(File binFile, String sha1) {
        GAV gav = null;
        try {
            URL url = new URL("http://repository.sonatype.org/service/local/data_index?sha1=" + sha1);
            URLConnection con = url.openConnection();
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Content-Type", "application/json");
            String json = IOUtils.toString(con.getInputStream());
            System.out.println(json);
            JSONObject jsonObject = JSONObject.fromObject(json);
            JSONArray data = jsonObject.getJSONArray("data");
            for (int i = 0; i < data.size(); i++) {
                JSONObject doci = data.getJSONObject(i);
                gav = new GAV();
                gav.setG(doci.getString("groupId"));
                gav.setA(doci.getString("artifectId"));
                gav.setV(doci.getString("version"));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return gav;
    }

    private static File getSourceFileFromGoogle(File binFile) {
        File result = null;
        try {
            String name = findRealName(binFile);
            result = downloadSourceFile(name);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    private static File downloadSourceFile(String name) throws Exception {
        File result = null;

        String srcName = name.substring(0, name.length() - ".jar".length()) + "-src.zip";
        String srcName2 = name.substring(0, name.length() - ".jar".length()) + ".zip";
        File file = new File(System.getProperty("user.home") + File.separatorChar
                + ".sourceattacher"+ File.separatorChar + srcName);
        if (file.exists()) {
            result = file;
        } else {
            URL url = new URL("http://ajax.googleapis.com/ajax/services/search/web?v=1.0&"
                    + "q=" + URLEncoder.encode(srcName + " intitle:\"index of\" \"Parent Directory\"", "UTF-8"));
            String json = IOUtils.toString(url.openStream());
            System.out.println(json);
            if (emptySearch(json)) {
                url = new URL("http://ajax.googleapis.com/ajax/services/search/web?v=1.0&"
                        + "q=" + URLEncoder.encode(srcName2 + " intitle:\"index of\" \"Parent Directory\"", "UTF-8"));
                json = IOUtils.toString(url.openStream());
                System.out.println(json);
            }
            JSONObject jsonObject = JSONObject.fromObject(json);
            if (jsonObject.getInt("responseStatus") == 200) {
                JSONObject responseData = jsonObject.getJSONObject("responseData");
                JSONArray array = responseData.getJSONArray("results");
                for (int i = 0; i < array.size(); i ++) {
                    JSONObject obj = array.getJSONObject(i);
                    URL url2 = new URL(obj.getString("url"));
                    URL srcUrl = new URL(url2, srcName);

                    HttpURLConnection con = (HttpURLConnection) srcUrl.openConnection();

                    con.setRequestMethod("HEAD");
                    if (con.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        DOMParser parser = new DOMParser();
                        parser.parse(url2.toString());
                        HTMLDocument document = (HTMLDocument) parser.getDocument();
                        HTMLCollection links = document.getLinks();
                        for (int ii = 0; ii < links.getLength(); ii++) {
                            Node link = links.item(ii);
                            NamedNodeMap attrs = link.getAttributes();
                            String href = null;
                            for (int j = 0; j < attrs.getLength(); j++) {
                                if ("href".equalsIgnoreCase(attrs.item(j).getNodeName())) {
                                    href = attrs.item(j).getNodeValue();
                                }
                            }
                            if (!href.startsWith("javascript:") && !href.startsWith("news:")) {
                                String absHref = new URL(url2, href).toString();
                                int lastSep = absHref.lastIndexOf('/');
                                int lastSharp = absHref.lastIndexOf('#');
                                if (lastSep >= 0 && lastSharp >= 0 && lastSharp > lastSep) {
                                    absHref = absHref.substring(0, lastSharp);
                                }
                                if (absHref.endsWith(".zip")) {
                                    String urlFile = absHref.substring(absHref.lastIndexOf('/'));
                                    Pattern pattern = Pattern.compile("[a-zA-Z]+");
                                    Matcher matcher = pattern.matcher(name);
                                    if (matcher.find()) {
                                        String nm = matcher.group();
                                        if (urlFile.contains(nm) && urlFile.contains("src")) {
                                            srcUrl = new URL(absHref);
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    byte[] data = IOUtils.toByteArray(srcUrl.openStream());
                    FileUtils.writeByteArrayToFile(file, data);
                    result = file;
                    break;
                }
            }
        }
        return result;
    }

    private static boolean emptySearch(String json) {
        boolean result = true;
        JSONObject jsonObject = JSONObject.fromObject(json);
        if (jsonObject.getInt("responseStatus") == 200) {
            JSONObject responseData = jsonObject.getJSONObject("responseData");
            JSONArray array = responseData.getJSONArray("results");
            result =  array.size() == 0;
        }
        return result;
    }

    private static String findRealName(File binFile) throws Exception {
        String name = null;

        // If the file name doesn't have version number, we need to google for a better name
        if (!Pattern.compile("\\-[0-9]+").matcher(binFile.getName()).find()) {
            // Guess real file name ([name]-[version].jar)
            String md5 = DigestUtils.md5Hex(FileUtils.openInputStream(binFile));
            URL url = new URL("http://ajax.googleapis.com/ajax/services/search/web?v=1.0&" + "q=" + md5);
            String json = IOUtils.toString(url.openStream());
            System.out.println(json);
            Map<String, Integer> counter = new HashMap<String, Integer>();
            String patternStr = "[a-zA-Z][a-zA-Z0-9\\-\\.]+\\.jar";
            Pattern pattern = Pattern.compile(patternStr);
            Matcher matcher = pattern.matcher(json);
            while (matcher.find()) {
                String nm = matcher.group();
                Integer count;
                if (counter.containsKey(nm)) {
                    count = new Integer(1 + counter.get(nm).intValue());
                } else {
                    count = new Integer(1);
                }
                counter.put(nm, count);
            }
            Integer max = new Integer(0);
            for (Map.Entry<String, Integer> entry : counter.entrySet()) {
                if (entry.getValue().compareTo(max) > 0) {
                    name = entry.getKey();
                }
            }

            if (name == null) {
                URL url2 = new URL("http://www.google.com/search?hl=vi&source=hp&biw=&bih=&q=" + md5);
                URLConnection con = url2.openConnection();
                con.setRequestProperty("User-Agent", "Mozilla 5.0 (Windows; U; "
                        + "Windows NT 5.1; en-US; rv:1.8.0.11) ");
                String html = IOUtils.toString(con.getInputStream());
                counter = new HashMap<String, Integer>();
                patternStr = "/([a-zA-Z\\.\\-\\_0-9]+)\\.md5";
                pattern = Pattern.compile(patternStr);
                matcher = pattern.matcher(html);
                while (matcher.find()) {
                    String nm = matcher.group(1);
                    Integer count;
                    if (counter.containsKey(nm)) {
                        count = new Integer(1 + counter.get(nm).intValue());
                    } else {
                        count = new Integer(1);
                    }
                    counter.put(nm, count);
                }
                max = new Integer(0);
                for (Map.Entry<String, Integer> entry : counter.entrySet()) {
                    if (entry.getValue().compareTo(max) > 0) {
                        name = entry.getKey();
                    }
                }
            }
        }

        // Not found on the web, use name of binFile
        if (name == null) {
            name = binFile.getName();
        }

        return name;
    }

    public static void main(String[] args) throws Exception {
        File bin = new File("D:/projects/free-plugins/org.freejava.javasourceattacher/lib/commons-logging-1.1.1.jar");
        File src = getSourceFileFromGoogle(bin);
        System.out.println(src);
    }

    public static File resolveArtifactSrc(GAV srcinfo) throws Exception {
        String groupId = srcinfo.getG();
        String artifactId = srcinfo.getA();
        String version = srcinfo.getV();

        //creates clear ivy settings
        IvySettings ivySettings = new IvySettings();
        IBiblioResolver resolver = new IBiblioResolver();
        resolver.setM2compatible(true);
        resolver.setName("central");
        ivySettings.addResolver(resolver);
        ivySettings.setDefaultResolver(resolver.getName());
        Ivy ivy = Ivy.newInstance(ivySettings);

        File ivyfile = File.createTempFile("ivy", ".xml");
        ivyfile.deleteOnExit();

        FileUtils.writeStringToFile(ivyfile,
        "<ivy-module version='2.0' xmlns:m='http://ant.apache.org/ivy/maven'><info organisation='apache' module='m2-with-sources' revision='1.0' /><dependencies><dependency org='" +
         groupId + "' name='" +artifactId+ "' rev='" + version + "' conf='default->sources' /></dependencies></ivy-module>");
        String[] confs = new String[]{"default"};
        ResolveOptions resolveOptions = new ResolveOptions().setConfs(confs);

        //init resolve report
        ResolveReport report = ivy.resolve(ivyfile.toURL(), resolveOptions);

        //so you can get the jar library
        File jarArtifactFile = report.getAllArtifactsReports()[0].getLocalFile();

        return jarArtifactFile;
    }

}