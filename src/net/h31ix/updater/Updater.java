/*
 * Updater for Bukkit.
 *
 * This class provides the means to safetly and easily update a plugin, or check to see if it is updated using dev.bukkit.org
 */

package net.h31ix.updater;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.XMLEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

/**
 * Check dev.bukkit.org to find updates for a given plugin, and download the updates if needed.
 * <p>
 * <b>VERY, VERY IMPORTANT</b>: Because there are no standards for adding auto-update toggles in your plugin's config, this system provides NO CHECK WITH YOUR CONFIG to make sure the user has allowed auto-updating.
 * <br>
 * It is a <b>BUKKIT POLICY</b> that you include a boolean value in your config that prevents the auto-updater from running <b>AT ALL</b>.
 * <br>
 * If you fail to include this option in your config, your plugin will be <b>REJECTED</b> when you attempt to submit it to dev.bukkit.org.
 * <p>
 * An example of a good configuration option would be something similar to 'auto-update: true' - if this value is set to false you may NOT run the auto-updater.
 * <br>
 * If you are unsure about these rules, please read the plugin submission guidelines: http://goo.gl/8iU5l
 * 
 * @author H31IX
 */

public class Updater 
{
    private Plugin plugin;
    private boolean checkVersion;
    private String versionTitle;
    private String versionLink;
    private URL url; // Connecting to RSS
    private static final String DBOUrl = "http://dev.bukkit.org/server-mods/"; // Slugs will be appended to this to get to the project's RSS feed
    private String [] noUpdateTag = {"-DEV","-PRE"}; // If the version number contains one of these, don't update.
    private static final int BYTE_SIZE = 1024; // Used for downloading files
    private String updateFolder = YamlConfiguration.loadConfiguration(new File("bukkit.yml")).getString("settings.update-folder"); // The folder that downloads will be placed in
    private Updater.UpdateResult result = Updater.UpdateResult.SUCCESS; // Used for determining the outcome of the update process
    
    // Strings for reading RSS
    private static final String TITLE = "title";
    private static final String LINK = "link";
    private static final String ITEM = "item";    
    
    public enum UpdateResult
    {
        /**
        * The updater found an update, and has readied it to be loaded the next time the server restarts/reloads.
        */        
        SUCCESS(1),
        /**
        * The updater did not find an update, and nothing was downloaded.
        */        
        NO_UPDATE(2),
        /**
        * The updater found an update, but was unable to download it.
        */        
        FAIL_DOWNLOAD(3),
        /**
        * For some reason, the updater was unable to contact dev.bukkit.org to download the file.
        */        
        FAIL_DBO(4),
        /**
        * When running the version check, the file on DBO did not contain the a version in the format 'vVersion' such as 'v1.0'.
        */        
        FAIL_NOVERSION(5),
        /**
        * The slug provided by the plugin running the updater was invalid and doesn't exist on DBO.
        */        
        FAIL_BADSLUG(6);
        
        private static final Map<Integer, Updater.UpdateResult> valueList = new HashMap<Integer, Updater.UpdateResult>();
        private final int value;
        
        private UpdateResult(int value)
        {
            this.value = value;
        }
        
        public int getValue()
        {
            return this.value;
        }
        
        public static Updater.UpdateResult getResult(int value)
        {
            return valueList.get(value);
        }
        
        static
        {
            for(Updater.UpdateResult result : Updater.UpdateResult.values())
            {
                valueList.put(result.value, result);
            }
        }
    }
    
    /**
     * Initialize the updater
     * 
     * @param plugin
     *            The plugin that is checking for an update.
     * @param slug
     *            The dev.bukkit.org slug of the project (http://dev.bukkit.org/server-mods/SLUG_IS_HERE)
     * @param file
     *            The file that the plugin is running from, get this by doing this.getFile() from within your main class.
     * @param checkVersion
     *            If true, the system will run a check comparing our version with the newest file to see if they differ before downloading.
     */ 
    public Updater(Plugin plugin, String slug, File file, boolean checkVersion)
    {
        this.plugin = plugin;
        this.checkVersion = checkVersion;
        try 
        {
            // Obtain the results of the project's file feed
            url = new URL(DBOUrl + slug + "/files.rss");
        } 
        catch (MalformedURLException ex) 
        {
            // The slug doesn't exist
            plugin.getLogger().warning("The author of this plugin has misconfigured their Auto Update system");
            plugin.getLogger().warning("The project slug added ('" + slug + "') is invalid, and does not exist on dev.bukkit.org");
            result = Updater.UpdateResult.FAIL_BADSLUG; // Bad slug! Bad!
        }
        if(url != null)
        {
            // Obtain the results of the project's file feed
            readFeed();
            if(versionCheck(versionTitle))
            {
                String fileLink = getFile(versionLink);
                if(fileLink != null)
                {
                    String name = file.getName();
                    // If it's a zip file, it shouldn't be downloaded as the plugin's name
                    if(fileLink.endsWith(".zip"))
                    {
                        String [] split = fileLink.split("/");
                        name = split[split.length-1];
                    }
                    saveFile(new File("plugins/" + updateFolder), name, fileLink);
                }
            }
        }
    }

    /**
     * Get the result of the update process.
     */     
    public Updater.UpdateResult getResult()
    {
        return result;
    }
    
    /**
     * Save an update from dev.bukkit.org into the server's update folder.
     */     
    private void saveFile(File folder, String file, String url)
    {
        if(!folder.exists())
        {
            folder.mkdir();
        }
        BufferedInputStream in = null;
        FileOutputStream fout = null;
        try
        {
            // Download the file
            in = new BufferedInputStream(new URL(url).openStream());
            fout = new FileOutputStream(folder.getAbsolutePath() + "/" + file);

            byte[] data = new byte[BYTE_SIZE];
            int count;
            while ((count = in.read(data, 0, BYTE_SIZE)) != -1)
            {
                fout.write(data, 0, count);
            }
            //Just a quick check to make sure we didn't leave any files from last time...
            for(File xFile : new File("plugins/" + updateFolder).listFiles())
            {
                if(xFile.getName().endsWith(".zip"))
                {
                    xFile.delete();
                }
            }
            // Check to see if it's a zip file, if it is, unzip it.
            File dFile = new File(folder.getAbsolutePath() + "/" + file);
            if(dFile.getName().endsWith(".zip"))
            {
                // Unzip
                unzip(dFile.getCanonicalPath());
            }
        }
        catch (Exception ex)
        {
            plugin.getLogger().warning("The auto-updater tried to download a new update, but was unsuccessful."); 
            result = Updater.UpdateResult.FAIL_DOWNLOAD;
        }
        finally
        {
            try
            {
                if (in != null)
                {
                    in.close();
                }
                if (fout != null)
                {
                    fout.close();
                }
            }
            catch (Exception ex)
            {              
            }
        }
    }
    
    /**
     * Zip-File-Extractor, modified by H31IX for use with Bukkit
     */      
    private void unzip(String file) 
    {
        try
        {
            File fSourceZip = new File(file);
            String zipPath = file.substring(0, file.length()-4);
            ZipFile zipFile = new ZipFile(fSourceZip);
            Enumeration e = zipFile.entries();
            while(e.hasMoreElements())
            {
                ZipEntry entry = (ZipEntry)e.nextElement();
                File destinationFilePath = new File(zipPath,entry.getName());
                destinationFilePath.getParentFile().mkdirs();
                if(entry.isDirectory())
                {
                    continue;
                }
                else
                {
                    BufferedInputStream bis = new BufferedInputStream(zipFile.getInputStream(entry));                     
                    int b;
                    byte buffer[] = new byte[BYTE_SIZE];
                    FileOutputStream fos = new FileOutputStream(destinationFilePath);
                    BufferedOutputStream bos = new BufferedOutputStream(fos, BYTE_SIZE);
                    while((b = bis.read(buffer, 0, BYTE_SIZE)) != -1) 
                    {
                        bos.write(buffer, 0, b);
                    }
                    bos.flush();
                    bos.close();
                    bis.close();
                    String name = destinationFilePath.getName();
                    if(name.endsWith(".jar") && pluginFile(name))
                    {
                        destinationFilePath.renameTo(new File("plugins/" + updateFolder + "/" + name));
                    }
                }
                entry = null;
                destinationFilePath = null;
            }
            e = null;
            zipFile.close();
            zipFile = null;
            // Move any plugin data folders that were included to the right place, Bukkit won't do this for us.
            for(File dFile : new File(zipPath).listFiles())
            {
                if(dFile.isDirectory())
                {
                    if(pluginFile(dFile.getName()))
                    {
                        File oFile = new File("plugins/" + dFile.getName()); // Get current dir
                        File [] contents = oFile.listFiles(); // List of existing files in the current dir
                        for(File cFile : dFile.listFiles()) // Loop through all the files in the new dir
                        {
                            boolean found = false;
                            for(File xFile : contents) // Loop through contents to see if it exists
                            {
                                if(xFile.getName().equals(cFile.getName()))
                                {
                                    found = true;
                                    break;
                                }
                            }
                            if(!found)
                            {
                                // Move the new file into the current dir
                                cFile.renameTo(new File(oFile.getCanonicalFile() + "/" + cFile.getName()));
                            }
                            else
                            {
                                // This file already exists, so we don't need it anymore.
                                cFile.delete();
                            }
                        }
                    }
                }
                dFile.delete();
            }
            new File(zipPath).delete();
            fSourceZip.delete();
        }
        catch(IOException ex)
        {
            ex.printStackTrace();
            plugin.getLogger().warning("The auto-updater tried to unzip a new update file, but was unsuccessful."); 
            result = Updater.UpdateResult.FAIL_DOWNLOAD;     
        } 
        new File(file).delete();
    } 
    
    /**
     * Check if the name of a jar is one of the plugins currently installed, used for extracting the correct files out of a zip.
     */       
    public boolean pluginFile(String name)
    {
        for(File file : new File("plugins").listFiles())
        {
            if(file.getName().equals(name))
            {
                return true;
            }
        }
        return false;
    }   
    
    /**
     * Obtain the direct download file url from the file's page.
     */    
    private String getFile(String link)
    {
        String download = null;
        try
        {
            // Open a connection to the page
            URL url = new URL(link);
            URLConnection urlConn = url.openConnection();
            InputStreamReader inStream = new InputStreamReader(urlConn.getInputStream());
            BufferedReader buff = new BufferedReader(inStream);
            
            String line;
            while((line = buff.readLine()) != null)
            {
                // Search for the download link
                if(line.contains("<li class=\"user-action user-action-download\">"))
                {
                    // Get the raw link
                    download = line.split("<a href=\"")[1].split("\">Download</a>")[0];
                }
            }
            urlConn = null;
            inStream = null;
            buff.close();
            buff = null;
        }
        catch (Exception ex)
        {
            plugin.getLogger().warning("The auto-updater tried to contact dev.bukkit.org, but was unsuccessful.");
            result = Updater.UpdateResult.FAIL_DBO;
            return null;            
        }
        return download;
    }
    
    /**
     * Check to see if the program should continue by evaluation whether the plugin is already updated, or shouldn't be updated
     */
    private boolean versionCheck(String title)
    {
        if(checkVersion)
        {
            String version = plugin.getDescription().getVersion();
            if(title.split("v").length == 2)
            {
                String remoteVersion = title.split("v")[1].split(" ")[0]; // Get the newest file's version number
                if(hasTag(version) || version.equalsIgnoreCase(remoteVersion))
                {
                    // We already have the latest version, or this build is tagged for no-update
                    result = Updater.UpdateResult.NO_UPDATE;
                    return false;
                }
            }
            else
            {
                // The file's name did not contain the string 'vVersion'
                plugin.getLogger().warning("The author of this plugin has misconfigured their Auto Update system");
                plugin.getLogger().warning("Files uploaded to BukkitDev should contain the version number, seperated from the name by a 'v', such as PluginName v1.0");
                plugin.getLogger().warning("Please notify the author (" + plugin.getDescription().getAuthors().get(0) + ") of this error.");
                result = Updater.UpdateResult.FAIL_NOVERSION;
                return false;
            }
        }
        return true;
    }
        
    /**
     * Evaluate whether the version number is marked showing that it should not be updated by this program
     */  
    private boolean hasTag(String version)
    {
        for(String string : noUpdateTag)
        {
            if(version.contains(string))
            {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Part of RSS Reader by Vogella, modified by H31IX for use with Bukkit
     */     
    @SuppressWarnings("null")
    private void readFeed() 
    {
        try 
        {
            // Set header values intial to the empty string
            String title = "";
            String link = "";
            // First create a new XMLInputFactory
            XMLInputFactory inputFactory = XMLInputFactory.newInstance();
            // Setup a new eventReader
            InputStream in = read();
            XMLEventReader eventReader = inputFactory.createXMLEventReader(in);
            // Read the XML document
            while (eventReader.hasNext()) 
            {
                XMLEvent event = eventReader.nextEvent();
                if (event.isStartElement()) 
                {
                    if (event.asStartElement().getName().getLocalPart().equals(TITLE)) 
                    {                  
                        event = eventReader.nextEvent();
                        title = event.asCharacters().getData();
                        continue;
                    }
                    if (event.asStartElement().getName().getLocalPart().equals(LINK)) 
                    {                  
                        event = eventReader.nextEvent();
                        link = event.asCharacters().getData();
                        continue;
                    }
                } 
                else if (event.isEndElement()) 
                {
                    if (event.asEndElement().getName().getLocalPart().equals(ITEM)) 
                    {
                        // Store the title and link of the first entry we get - the first file on the list is all we need
                        versionTitle = title;
                        versionLink = link;
                        // All done, we don't need to know about older files.
                        break;
                    }
                }
            }
        } 
        catch (XMLStreamException e) 
        {
            throw new RuntimeException(e);
        }
    }  

    /**
     * Open the RSS feed
     */    
    private InputStream read() 
    {
        try 
        {
            return url.openStream();
        } 
        catch (IOException e) 
        {
            throw new RuntimeException(e);
        }
    }
}
