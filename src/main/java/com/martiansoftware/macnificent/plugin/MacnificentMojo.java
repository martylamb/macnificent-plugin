package com.martiansoftware.macnificent.plugin;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;

// process:
// 1: ensure a current cache copy in local repository under com/martiansoftware/macnificent/macnificent-cache.jar
//    http://www.oreillynet.com/onjava/blog/2004/07/optimizing_http_downloads_in_j.html
//    http://www.hackdiary.com/2003/04/09/using-http-conditional-get-in-java-for-efficient-polling/
//    https://devcenter.heroku.com/articles/increasing-application-performance-with-http-cache-headers
// 2: if resource to generate already exists, compare timestamp to cache.  if resource newer than cache, complete successfully
// 3: generate resource from cache
/**
 * 
 * @author mlamb
 */
@Mojo(name = "macnificent.dat", defaultPhase = LifecyclePhase.GENERATE_RESOURCES)
public class MacnificentMojo extends AbstractMojo {
    
    private static final String CACHE_FILENAME = "com/martiansoftware/macnificent/misc/macnificent-cache.jar";
    private static final String H_ETAG = "ETag";
    private static final String H_LASTMODIFIED = "Last-Modified";
    private static final String ZE_HEADERS = "headers.txt";
    private static final String ZE_DATA = "oui.txt";
    private static final int BUFSIZE = 4096;
    
    @Parameter (defaultValue="${project}", required=true, readonly=true)            
    private MavenProject project;

    @Component
    private MavenProjectHelper projectHelper;

    @Parameter (defaultValue="${localRepository}", required=true, readonly=true)
    private org.apache.maven.artifact.repository.ArtifactRepository localRepository;

    @Parameter(property = "macnificent.datfile", defaultValue = "macnificent.dat")
    private String file;
    
    @Parameter(property = "macnificent.url", defaultValue = "http://standards.ieee.org/develop/regauth/oui/oui.txt")
    private String url;

    // TODO: is this separator char ok on windows?
    @Parameter(property = "macnificent.datdir", defaultValue = "target/generated-resources/macnificent-plugin")
    private String dir;
    
    @Parameter(property = "macnificent.offline", defaultValue = "false")
    private boolean offline;
    
    // TODO: skip if already generated
    public void execute() throws MojoExecutionException {
        File repoDir = ensureDirectory(new File(localRepository.getBasedir()));
        File cacheFile = updateCache(repoDir);
        if (!cacheFile.exists()) throw new MojoExecutionException("No data available: " + cacheFile.getAbsolutePath());
        File resourceDir = initResourceDir();
        generateDataFile(cacheFile, resourceDir);
    }
    
    private File updateCache(File repoDir) throws MojoExecutionException {
        File cacheFile = new File(repoDir, CACHE_FILENAME);
        if (offline) {
            getLog().info("Operating in offline mode; skipping connection to " + url);
        } else {
            Properties cachedHeaders = getHeaders(cacheFile);

            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setFollowRedirects(true);
                conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
                if (cachedHeaders.containsKey(H_ETAG)) conn.addRequestProperty("If-None-Match", cachedHeaders.getProperty(H_ETAG));
                if (cachedHeaders.containsKey(H_LASTMODIFIED)) conn.addRequestProperty("If-Modified-Since", cachedHeaders.getProperty(H_LASTMODIFIED));
                conn.connect();
//                getLog().info("Encoding: " + conn.getContentEncoding());
//                getLog().info("ContentType: " + conn.getContentType());
                int rsp = conn.getResponseCode();
//                getLog().info("Response: " + conn.getResponseCode());
//                getLog().info("Cache: " + cacheFile.getAbsolutePath());
                if (rsp == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    getLog().info("Remote OUI data not changed; using cached data at " + cacheFile.getAbsolutePath());
                    conn.disconnect();
                    return cacheFile;
                }
                
                // need to update cache file.  download to a tmp file so we don't destroy the cache until we've received everything.
                File tmpFile = new File(repoDir, CACHE_FILENAME + ".tmp-" + UUID.randomUUID());
                ensureDirectory(tmpFile.getParentFile());
                
                tmpFile.deleteOnExit();
                try (ZipOutputStream zout = new ZipOutputStream(new FileOutputStream(tmpFile))) {
                    cachedHeaders.clear();
                    for(String hf : conn.getHeaderFields().keySet()) { // assume we only care about one value per header field.
                        if (hf != null) cachedHeaders.setProperty(hf, conn.getHeaderField(hf));
                    }
                    zout.putNextEntry(new ZipEntry(ZE_HEADERS));
                    cachedHeaders.store(zout, null);
                    zout.closeEntry();
                                        
                    InputStream din = conn.getInputStream();
                    if ("gzip".equals(conn.getContentEncoding())) din = new GZIPInputStream(conn.getInputStream());
                    if ("deflate".equals(conn.getContentEncoding())) din = new InflaterInputStream(conn.getInputStream());
                    zout.putNextEntry(new ZipEntry(ZE_DATA));
                    getLog().info("Downloading " + url + "...");
                    byte[] buf = new byte[BUFSIZE];
                    int len;
                    while ((len = din.read(buf)) > 0) zout.write(buf, 0, len);
                    zout.closeEntry();
                    zout.close();
                    
                    // cached the data, so let's really update the cache.
                    cacheFile.delete();
                    if (!tmpFile.renameTo(cacheFile)) throw new MojoExecutionException("Unable to move " + tmpFile.getAbsolutePath() + " to " + cacheFile.getAbsolutePath());
                    getLog().info("Cached data at " + cacheFile.getAbsolutePath());
                }                
                
            } catch (MalformedURLException e) {
                throw new MojoExecutionException("Bad URL: " + url);
            } catch (IOException e) {
                throw new MojoExecutionException("Unable to retrieve " + url + ": " + e.getMessage(), e);
            }
        }
        return cacheFile;        
    }
        
    private Properties getHeaders(File cacheFile) {
        Properties p = new Properties();
        if (cacheFile.exists()) {
            try (ZipFile zf = new ZipFile(cacheFile)) {
                ZipEntry ze = zf.getEntry(ZE_HEADERS);
                if (ze == null) throw new IOException("Header information not present.");
                p.load(zf.getInputStream(ze));
            } catch (IOException e) {
                getLog().warn("Bad cache file '" + cacheFile.getAbsolutePath() + "':\n    " + e.getMessage() + "\nDestroying cache.");
                cacheFile.delete();
            }
        }
        return p;
    }
        
    /**
     * Creates ${macnificent.datdir} directory if necessary, configures project
     * to use it for resources, and returns the dir.
     */
    private File initResourceDir() throws MojoExecutionException {
        File resourceDir = ensureDirectory(new File(project.getBasedir(), dir));
        projectHelper.addResource(project, resourceDir.getAbsolutePath(), Collections.singletonList("**/*"), null);        
        return resourceDir;
    }
    
    private File ensureDirectory(File dir) throws MojoExecutionException {
        if (!dir.exists() || !dir.isDirectory()) {
            if (!dir.mkdirs()) throw new MojoExecutionException("Unable to create directory '" + dir.getAbsolutePath() + "'");
        }
        return dir;
    }
    
    private void generateDataFile(File cacheFile, File resourceDir) throws MojoExecutionException {
        File resourceFile = new File(resourceDir, file);
        getLog().info("Creating resource " + resourceFile.getAbsolutePath() + "...");
        try (DataOutputStream dout = new DataOutputStream(new FileOutputStream(resourceFile))) {
            ZipFile zf = new ZipFile(cacheFile);
            ZipEntry ze = zf.getEntry(ZE_DATA);
            if (ze == null) {
                cacheFile.delete();
                throw new IOException("Bad cache file '" + cacheFile.getAbsolutePath() + "': No data in cache file. Destroying cache.");
            }
            generateData(zf.getInputStream(ze), dout);
        } catch (IOException e) {
            throw new MojoExecutionException("Error creating resource '" + resourceFile.getAbsolutePath() + "': " + e.getMessage(), e);
        }
    }
    
    private void generateData(InputStream in, DataOutputStream dout) throws IOException {
        Matcher m = Pattern.compile("^\\s*([0-9a-fA-f]{2})([0-9a-fA-f]{2})([0-9a-fA-f]{2})\\s+\\(base 16\\)\\s+(.*)$").matcher("");
        byte[] oui = new byte[3];
        int ouicount = 0;
        LineNumberReader r = new LineNumberReader(new InputStreamReader(in));        
        dout.writeLong(System.currentTimeMillis());
        String s = r.readLine();
        while (s != null) {
            m.reset(s);
            if (m.matches()) {
                for (int i = 0; i < 3; ++i) oui[i] = (byte) Integer.parseInt(m.group(i + 1), 16);
                dout.write(oui, 0, 3);
                dout.writeUTF(m.group(4));
                ++ouicount;
            }
            s = r.readLine();
        }
        in.close();
        dout.close();
        getLog().info("Added " + ouicount + " OUIs.");
    }
}
